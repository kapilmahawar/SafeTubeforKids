package tv.safetubeforkids.app.diag

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.io.File
import java.io.FileOutputStream

/**
 * W14.14 - passive diagnostic for the 0x0 ComposeView wedge.
 *
 * The failure this exists to catch, observed once on the family's Mi Box and never reproduced since:
 * the window, the DecorView, the LinearLayout and `android:id/content` are all correctly laid out at
 * 1920x1080, while the ComposeView (and therefore its AndroidComposeView child) sits at 0,0-0,0. The
 * screen is then a blank window background and accessibility sees three framework nodes, because a
 * zero-sized view is not visible to the user.
 *
 * This file OBSERVES. It must never repair:
 *
 *  - no requestLayout(), no invalidate(), no post/postDelayed that could nudge a layout along;
 *  - no recreate()/finish(), no setContentView(), no view-tree surgery, no semantics changes;
 *  - no window format/flag changes;
 *  - the content-attach path is untouched on purpose, because replacing or wrapping the Compose root
 *    would change the exact sequence under investigation and could hide the failure.
 *
 * Every hook attached here is a read-only listener, and the ones that fire often (global layout,
 * pre-draw) detach themselves after a bounded number of calls. The whole facility lives in the debug
 * source set, so nothing in it can reach a release build.
 *
 * Evidence is written to a fixed app-private file as well as logcat, because the interesting failure is
 * one where the process may be killed or the log ring buffer may roll over before anyone looks. The file
 * is opened per line and written directly (unbuffered), so a flushed record survives the process.
 */
object LayoutWedgeProbe {

    private const val TAG = "SafeTubeDiag"
    private const val FILE_NAME = "layout-wedge.log"

    /** Hard cap on the diagnostic file: the app under observation is a family's television. */
    private const val MAX_BYTES = 200_000L

    /** Bounded listener lifetimes - enough to cover startup, then silent. */
    private const val MAX_GLOBAL_LAYOUTS = 40
    private const val MAX_PRE_DRAWS = 10
    private const val MAX_DECOR_LAYOUTS = 60

    /** Bounded wedge snapshots, so a stuck instance cannot log forever. */
    private const val MAX_WEDGE_SNAPSHOTS = 5

    /**
     * Long-tail heartbeat.
     *
     * The failure this exists to catch happened in an instance that had been alive for a long time, not
     * in the first minute of a process - so the startup samples above could never have seen it. A
     * heartbeat is therefore kept for the life of the process, but it writes only when the observed
     * state has actually changed. A television that is simply working writes nothing at all, and the
     * file stays small; a ComposeView that goes to 0x0, a change of window visibility, or a lifecycle
     * step is recorded with full state within one interval. This is a change detector, not a poll log.
     */
    private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * Sampling schedule for the passive watchdog. The first second covers the interesting window - on
     * this device the app takes about four seconds to display - and the tail proves the state is stable
     * rather than merely slow.
     */
    private val sampleDelaysMs = longArrayOf(250, 500, 1000, 2000, 4000, 8000, 15000, 30000, 60000)

    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()
    private val pid = Process.myPid()

    private var logFile: File? = null
    private var bytesWritten = 0L
    private var globalLayouts = 0
    private var preDraws = 0
    private var wedgeSnapshots = 0
    private var firstNonZeroLogged = false
    private var activityResumed = false
    private var lastHeartbeatSignature: String? = null
    private var observedActivity = 0
    private var attachedComposeView = 0
    private var decorObserverAttached = false

    fun install(context: Context) {
        if (logFile != null) return
        val file = File(context.filesDir, FILE_NAME)
        logFile = file
        val version = runCatching { tv.safetubeforkids.app.BuildConfig.VERSION_NAME }.getOrDefault("?")
        record("=== SESSION pid=$pid version=$version startedAt=${System.currentTimeMillis()} ===")
    }

    /** Lifecycle breadcrumbs (called from the debug-only provider's ActivityLifecycleCallbacks). */
    fun lifecycle(activity: Activity, event: String) {
        if (event == "onActivityResumed") activityResumed = true
        if (event == "onActivityPaused" || event == "onActivityStopped") activityResumed = false
        record("LIFECYCLE $event act=${activity.javaClass.simpleName}@${System.identityHashCode(activity)} resumed=$activityResumed")
    }

    /**
     * First observation point.
     *
     * Measurement on the device showed this is called while `android:id/content` is still EMPTY: at
     * +972ms into a cold start the content frame had childCount=0, so setContent had not attached
     * anything yet at that moment. The observers therefore cannot assume the ComposeView exists. The
     * DecorView does exist here, so the DecorView is watched until the content child appears, and only
     * then are the ComposeView observers attached - that keeps the first traversal observable instead of
     * relying on a lifecycle callback whose ordering is not what it looks like on paper.
     */
    fun observe(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            record("OBSERVE no android.R.id.content act=${activity.javaClass.simpleName}")
            return
        }
        if (observedActivity != System.identityHashCode(activity)) {
            observedActivity = System.identityHashCode(activity)
            record(
                "OBSERVE_EARLY content=${describe(content)} childCount=${content.childCount} " +
                    "decor=${describe(activity.window.decorView)}",
            )
        }
        attachDecorLayoutObserver(activity)
        tryAttachToComposeView(activity)
    }

    /**
     * Attach the ComposeView observers once, the first time `android:id/content` has a child.
     * Idempotent: every later call for the same view is a no-op.
     */
    private fun tryAttachToComposeView(activity: Activity): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val composeView: View = content.getChildAt(0) ?: run {
            record("COMPOSEVIEW_NOT_YET_PRESENT childCount=${content.childCount} " + snapshot(activity))
            return false
        }
        if (System.identityHashCode(composeView) == attachedComposeView) return true
        attachedComposeView = System.identityHashCode(composeView)
        // setContent has just run, so its effect is visible here: this is the "setContent milestone",
        // recorded by observing the result rather than by hooking the call.
        record(
            "SETCONTENT_IMPLIED content=${describe(content)} childCount=${content.childCount} " +
                "child0=${describe(composeView)} child0LP=${layoutParams(composeView)}",
        )
        record("OBSERVE_STATE " + snapshot(activity))

        composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                record("CV_ATTACHED_TO_WINDOW " + snapshot(activity))
                attachWindowObserver(activity, v)
            }

            override fun onViewDetachedFromWindow(v: View) {
                record("CV_DETACHED_FROM_WINDOW " + snapshot(activity))
            }
        })

        // The decisive hook for a transition: fires only when the ComposeView's bounds actually change,
        // so it records "previous -> new" without any polling and without costing a frame.
        composeView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val changed = left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom
            record(
                "CV_LAYOUT changed=$changed new=[$left,$top-$right,$bottom] wh=${right - left}x${bottom - top} " +
                    "prev=[$oldLeft,$oldTop-$oldRight,$oldBottom] " + snapshot(activity),
            )
            if (right - left > 0 && bottom - top > 0 && !firstNonZeroLogged) {
                firstNonZeroLogged = true
                record("CV_FIRST_NONZERO_LAYOUT wh=${right - left}x${bottom - top} " + snapshot(activity))
            }
            checkForWedge(activity, "cv-layout")
        }

        if (composeView.isAttachedToWindow) attachWindowObserver(activity, composeView)

        // Declared as object expressions rather than lambdas because each removes itself after its
        // bounded run, and only inside the listener does `this` mean the listener.
        val globalLayout = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                globalLayouts++
                record("GLOBAL_LAYOUT #$globalLayouts " + snapshot(activity))
                checkForWedge(activity, "global-layout#$globalLayouts")
                if (globalLayouts >= MAX_GLOBAL_LAYOUTS) {
                    val observer = composeView.viewTreeObserver
                    if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                }
            }
        }
        composeView.viewTreeObserver.addOnGlobalLayoutListener(globalLayout)

        // A pre-draw callback is the last moment before the surface is handed a frame, so it is the
        // honest in-process proxy for "a draw happened". It is never asked to draw anything.
        val preDraw = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                preDraws++
                record("PRE_DRAW #$preDraws (firstFrame=$preDraws) " + snapshot(activity))
                checkForWedge(activity, "pre-draw#$preDraws")
                if (preDraws >= MAX_PRE_DRAWS) {
                    val observer = composeView.viewTreeObserver
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                }
                return true
            }
        }
        composeView.viewTreeObserver.addOnPreDrawListener(preDraw)

        scheduleSamples(activity)
        scheduleHeartbeat(activity)
        return true
    }

    /**
     * DecorView-level layout watch, attached while the window is still pre-layout.
     *
     * It exists because the Compose view may not be present yet at the first lifecycle callback: each
     * pass simply checks whether `android:id/content` has acquired its child and attaches the
     * ComposeView observers at that moment. Bounded, and it removes itself.
     */
    private fun attachDecorLayoutObserver(activity: Activity) {
        if (decorObserverAttached) return
        decorObserverAttached = true
        val decor = activity.window.decorView
        var passes = 0
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                passes++
                if (!tryAttachToComposeView(activity)) {
                    record("DECOR_LAYOUT #$passes content still empty " + snapshot(activity))
                }
                if (passes >= MAX_DECOR_LAYOUTS) {
                    val observer = decor.viewTreeObserver
                    if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                }
            }
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun attachWindowObserver(activity: Activity, view: View) {
        view.viewTreeObserver.addOnWindowAttachListener(object : ViewTreeObserver.OnWindowAttachListener {
            override fun onWindowAttached() {
                record("VT0_WINDOW_ATTACHED " + snapshot(activity))
            }

            override fun onWindowDetached() {
                record("VT0_WINDOW_DETACHED " + snapshot(activity))
            }
        })
    }

    /**
     * Passive samples at fixed moments. They exist because the failure may be a state that persists
     * without any event firing at all: if nothing ever lays the ComposeView out again, no layout
     * listener ever fires, and only a sample can show that the 0x0 state is still there.
     */
    private fun scheduleSamples(activity: Activity) {
        val reference = java.lang.ref.WeakReference(activity)
        sampleDelaysMs.forEachIndexed { index, delay ->
            handler.postDelayed({
                val host = reference.get() ?: return@postDelayed
                record("SAMPLE #${index + 1} (+${delay}ms) " + snapshot(host))
                checkForWedge(host, "sample#${index + 1}")
            }, delay)
        }
    }

    /**
     * Change-detected heartbeat for the whole life of the process (see [HEARTBEAT_INTERVAL_MS]).
     *
     * An unchanged state writes nothing, so a working television produces no traffic; any change - the
     * ComposeView's bounds, window visibility, layout or attachment state, or a lifecycle step - is
     * recorded once, with full state. It never repairs anything and it uses a weak reference, so a
     * destroyed activity simply ends the heartbeat.
     */
    private fun scheduleHeartbeat(activity: Activity) {
        val reference = java.lang.ref.WeakReference(activity)
        val beat = object : Runnable {
            override fun run() {
                val host = reference.get()
                if (host != null) {
                    val current = snapshot(host)
                    if (current != lastHeartbeatSignature) {
                        lastHeartbeatSignature = current
                        record("HEARTBEAT_CHANGED $current")
                        checkForWedge(host, "heartbeat")
                    }
                }
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        handler.postDelayed(beat, HEARTBEAT_INTERVAL_MS)
    }

    /**
     * PASSIVE DETECTOR. It reports and does nothing else.
     *
     * The condition is deliberately narrow so that it cannot fire during the normal pre-layout instant
     * that every healthy start passes through: the parent `android:id/content` must itself be laid out
     * and non-zero before a zero-sized ComposeView counts as a divergence.
     */
    private fun checkForWedge(activity: Activity, label: String) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val composeView = content.getChildAt(0) ?: return
        val zeroSized = composeView.width == 0 || composeView.height == 0
        val parentLaidOutNonZero = content.isLaidOut && content.width > 0 && content.height > 0
        if (!zeroSized || !parentLaidOutNonZero) return
        if (wedgeSnapshots >= MAX_WEDGE_SNAPSHOTS) return
        wedgeSnapshots++
        record("WEDGE_DETECTED label=$label snapshot=$wedgeSnapshots " + snapshot(activity))
        record("WEDGE_DETECTED_DETAIL cv=${describe(composeView)} cvLP=${layoutParams(composeView)} " +
            "content=${describe(content)} decor=${describe(activity.window.decorView)}")
    }

    /** One line of observable state, assembled only from public View/Window API. */
    private fun snapshot(activity: Activity): String {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val composeView = content?.getChildAt(0)
        val attributes = activity.window.attributes
        val androidComposeChild = (composeView as? ViewGroup)?.getChildAt(0)
        return buildString {
            append("act=").append(activity.javaClass.simpleName)
            append(" life=").append(if (activityResumed) "resumed" else "not-resumed")
            append(" finishing=").append(activity.isFinishing)
            append(" winFocus=").append(activity.hasWindowFocus())
            append(" thread=").append(Thread.currentThread().name)
            append(" cv=").append(describe(composeView))
            append(" cvLP=").append(layoutParams(composeView))
            append(" cvChild0=").append(describe(androidComposeChild))
            append(" cvWinVis=").append(composeView?.windowVisibility)
            append(" cvAttached=").append(composeView?.isAttachedToWindow)
            append(" cvLaidOut=").append(composeView?.isLaidOut)
            append(" cvShown=").append(composeView?.isShown)
            append(" cvAlpha=").append(composeView?.alpha)
            append(" cvVis=").append(visibilityName(composeView?.visibility))
            append(" cvRoot=").append(composeView?.rootView?.javaClass?.simpleName)
            append(" content=").append(describe(content))
            append(" decor=").append(describe(activity.window.decorView))
            append(" winFmt=").append(formatName(attributes.format))
            append(" winFlags=0x").append(Integer.toHexString(attributes.flags))
            append(" winType=").append(attributes.type)
        }
    }

    private fun describe(view: View?): String {
        if (view == null) return "null"
        return "${view.javaClass.simpleName}[${view.left},${view.top}-${view.right},${view.bottom}]" +
            " m=${view.measuredWidth}x${view.measuredHeight} vis=${visibilityName(view.visibility)}"
    }

    /**
     * The content view's LayoutParams are recorded because they decide what a zero result can mean:
     * Compose's own `setContent` attaches the root with MATCH_PARENT, and a MATCH_PARENT child cannot
     * legitimately measure to zero once its parent has been laid out.
     */
    private fun layoutParams(view: View?): String {
        val params = view?.layoutParams ?: return "null"
        return "${params.width}x${params.height}/${params.javaClass.simpleName}"
    }

    private fun visibilityName(visibility: Int?): String = when (visibility) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "?$visibility"
    }

    private fun formatName(format: Int): String = when (format) {
        android.graphics.PixelFormat.TRANSLUCENT -> "TRANSLUCENT($format)"
        android.graphics.PixelFormat.TRANSPARENT -> "TRANSPARENT($format)"
        android.graphics.PixelFormat.OPAQUE -> "OPAQUE($format)"
        else -> "fmt($format)"
    }

    private fun record(line: String) {
        val message = "+${SystemClock.elapsedRealtime() - startedAt}ms pid=$pid $line"
        Log.i(TAG, message)
        val file = logFile ?: return
        if (bytesWritten >= MAX_BYTES) return
        try {
            // Unbuffered append: one write per record, so a record that returned from write() is on disk
            // even if the process is killed immediately afterwards.
            FileOutputStream(file, true).use { stream ->
                stream.write((message + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            bytesWritten += message.length + 1
        } catch (t: Throwable) {
            Log.w(TAG, "diagnostic write failed: ${t.message}")
        }
    }
}
