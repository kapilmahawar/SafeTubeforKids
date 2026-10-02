# W13.2 — the player transport controls: DEFERRED

```text
STATUS=DEFERRED
SEVERITY=MEDIUM
CORE_PLAYBACK_BLOCKED=NO
SECURITY_IMPACT=NONE_IDENTIFIED
ROOT_CAUSE_STATUS=PARTIALLY_IDENTIFIED
```

Recorded at the W13 stable checkpoint, `W13-STABLE-2026-10-01`
(`54095dcd60df786f9aeb75f38a3859a8388f787d`), after two implementation attempts were made, measured on
the television, and reverted. Nothing from either attempt is in the product.

## The issue

The player's bottom deck shows five transport controls:

```text
Previous    Rewind 10 seconds    Play / Pause    Forward 10 seconds    Next
```

They render, they carry the right labels, and the playback behaviour behind them already exists (the
remote's media keys drive `PlaybackController`). What is not reliable is **D-pad focus traversal through
them**: the player's full-screen surface can hold focus instead of letting the remote walk Previous →
Rewind → Play/Pause → Forward → Next.

On this device that matters more than it looks: a Mi Box remote has **no media keys**, so the arrow keys
are the transport. The controls are visible but the remote cannot reliably reach them.

## What the investigation established

1. **The transport controls are focusable.** The first attempt's own accessibility dump shows the real
   focus target is the parent Compose/Android `View` wrapper, not the inner icon:

   ```text
   class=android.view.View       focusable=true  focused=true   bounds=[112,932][208,1028]   <- Previous wrapper
   class=android.widget.ImageView focusable=false desc='Previous'  bounds=[134,954][186,1006] <- inner icon
   ```

   The `ImageView` nodes carrying the labels are the inner `Icon`s (Compose reports an Image-role
   semantics node as `android.widget.ImageView`), and they are correctly not focus targets. Reading them
   as "the controls are not focusable" was an early mistake in this investigation and is recorded here so
   it is not repeated.

2. **Losing the row on the first press was one observed failure mode.** In that same attempt focus *did*
   land on the Previous wrapper, and the first RIGHT press moved it to the full-screen surface
   (`class=android.view.View bounds=[0,0][1920,1080]`) rather than to Rewind. The surface is a focus
   target whose bounds contain every control, so it is a candidate in every direction for Compose's
   geometric directional search.

3. **An explicit focus graph did not fix it.** A second attempt added one `FocusRequester` per control,
   explicit `focusProperties { left = …; right = … }` links (with the ends naming themselves, closing the
   row into a loop), and `focusGroup()` on the row, leaving the surface focusable and the screen's key
   handler consuming UP/DOWN to leave the row. It compiled, was built and installed (verified by the
   package's `lastUpdateTime` moving from `2026-10-01 23:59:23` to `2026-10-02 06:10:12`), and on the
   television **focus did not reliably enter the controls at all**: every dump after entering the row and
   after four RIGHT presses reported the full-screen surface focused. With nothing focused inside the row,
   the explicit neighbour links had nothing to move.

4. **So the exact Compose focus architecture around this overlay is not yet understood.** The two attempts
   differ in how entry was requested (a direct `requestFocus()` first, a `LaunchedEffect`-deferred request
   second) and in the added focus group and properties, and the second behaved *worse* at entry than the
   first. That is a hypothesis, not a measurement: the state inside the app was never observed directly.

## The invariant that must not be lost

```text
The transport-focus problem is a player UX/focus-navigation issue.

It does not change the existing PlaybackAuthorization security boundary.

Previous/Next must continue to use the existing authorized queue when this feature is revisited.
```

The attempted implementation did this correctly — each control called the same callback the remote's keys
call (`onNextApproved` / `onPreviousApproved` → `PlaybackController.next()` / `previous()` → the approved
queue) and no playback logic was duplicated in the UI. **A future implementation must not introduce a
direct-playback shortcut to make the buttons "work".** Catalog membership is still not playback
authorization, and no UI control may bypass `PlaybackAuthorization`.

## When this is resumed

1. **Instrument first, do not change modifiers first.** `uiautomator` misled this investigation three
   times (the `ImageView` reading; a truncated 11-node dump reported as "nothing focused"; and the
   television's photo screensaver presenting its own full-screen `RelativeLayout`, which looks exactly
   like the player surface in a dump). Log focus *inside the app* before touching anything: which node
   holds focus and when, and whether `requestFocus()` threw.
2. **Then choose between the two candidate designs with evidence:** either a programmatic request from a
   composition effect that proves it resolved to the row's first control, or dropping the request
   entirely and letting Compose traversal move focus into the row (leaving DOWN unconsumed at the settings
   row) while keeping explicit left/right links for the horizontal walk.
3. Rebuild and install deliberately (never `-SkipBuild`) — the last installed APK in this line of work was
   from a reverted attempt, which is exactly the kind of thing that makes a later result meaningless.
4. Only once focus traversal is measured should the five actions be verified, then the Previous/Next
   authorization oracle, then the matrix.

Unverified material from the two attempts is preserved outside the repository and should be read, not
blindly applied: `%TEMP%\w132\w132-transport.patch` (implementation, harness checks, unit test, first
record) and `%TEMP%\w132b\w132b-focus-graph.patch` (the explicit focus graph). Both are temporary files.

## Environment notes for whoever resumes

* The television's photo screensaver (`com.furnaghan.android.photoscreensaver`) takes focus when the TV
  is idle. The harness's W12.3 foreground guard catches it correctly and refuses to deliver keys, but a
  tier then fails its library precondition — that is the environment, not the app. Disable it for a run
  and restore it afterwards (`settings put secure screensaver_enabled`).
* A Mi Box reboot has twice come back with its firmware-default clock (October 2023), which makes every
  HTTPS resolve fail with `Unacceptable certificate: CN=WR2, O=Google Trust Services`. Re-arming
  automatic time fixes it; it looks exactly like a resolver failure.
* ADB authorization for this host has been lost across reboots too — `adb devices` reports `unauthorized`
  until the prompt is accepted on the television.
