package tv.safetubeforkids.app.ui.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidFocusRing
import tv.safetubeforkids.app.ui.theme.KidFocusRingBright
import tv.safetubeforkids.app.ui.theme.KidFocusRingHalo
import tv.safetubeforkids.app.ui.theme.KidSurface

/**
 * A focus indicator has to be *visible*, which is a measurable property rather than a matter of taste.
 *
 * W13.3 measured the player on the TV rather than judging it by eye: the transport controls' ring was
 * `KidFocusRing` (the accent at 60%) painted on top of the disc, and over Play/Pause's opaque accent disc
 * that composites to the disc's own colour - 1.00:1 against the fill, identical pixels, no indicator at all
 * on the most-used control - while the other four reached only 1.12-1.43:1. On the error screen the two
 * buttons differed by 1.08:1 through Material's tonal elevation, with no ring on either.
 *
 * A device test cannot assert pixels, so these are the invariants that keep the indicator from regressing
 * into "invisible", computed from the very colours the player draws with. They are deliberately about
 * contrast rather than about specific hex values, so a palette change is allowed as long as the indicator
 * stays legible.
 */
class FocusIndicatorContrastTest {

    /** The composed sRGB colour of `source` drawn over an opaque `background`. */
    private fun over(source: Color, background: Color): Color = Color(
        red = source.red * source.alpha + background.red * (1f - source.alpha),
        green = source.green * source.alpha + background.green * (1f - source.alpha),
        blue = source.blue * source.alpha + background.blue * (1f - source.alpha),
        alpha = 1f,
    )

    /** WCAG relative luminance of an sRGB colour. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `an accent ring on the accent disc is the disc's own colour`() {
        // This is why the transport ring is no longer drawn inside the disc: on Play/Pause the fill is
        // opaque KidAccent, so the accent-at-60% ring composited to exactly the fill. On the TV that came
        // out as ring RGB (34,165,89) against fill RGB (34,165,89).
        val onDisc = over(KidFocusRing, KidAccent)
        assertEquals("the old ring's red was the disc's red", KidAccent.red, onDisc.red, 0.002f)
        assertEquals("the old ring's green was the disc's green", KidAccent.green, onDisc.green, 0.002f)
        assertEquals("the old ring's blue was the disc's blue", KidAccent.blue, onDisc.blue, 0.002f)
        assertEquals("so it contributed no contrast at all", 1.0, contrast(onDisc, KidAccent), 0.01)
    }

    @Test
    fun `the ring contrasts with the disc it is drawn around`() {
        // The accent is a fairly light colour, so a white ring on it cannot reach 3:1 on its own - the dark
        // halo on its other side is what carries the indicator. 2.5 is the floor for telling them apart.
        val ratio = contrast(KidFocusRingBright, KidAccent)
        assertTrue("bright ring against the accent disc was $ratio:1", ratio >= 2.5)
    }

    @Test
    fun `the halo separates the ring from bright video`() {
        val ratio = contrast(KidFocusRingBright, KidFocusRingHalo)
        assertTrue("bright ring against its halo was $ratio:1", ratio >= 3.0)
    }

    @Test
    fun `the focused menu action is not merely a shade of the unfocused one`() {
        // The error screen's two buttons: focused is the accent, unfocused is the surface behind it. Before
        // W13.3 both were accent-filled and differed only by Material's elevation overlay, measured at
        // 1.08:1 on the TV ((56,173,105) against (34,165,89)).
        val ratio = contrast(KidAccent, KidSurface)
        assertTrue("focused against unfocused was $ratio:1", ratio >= 3.0)
    }
}
