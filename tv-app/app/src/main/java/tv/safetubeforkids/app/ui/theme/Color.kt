package tv.safetubeforkids.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Kid palette (HomeScreen, PlaybackScreen) — warm dark
val KidBackground = Color(0xFF1C1917)    // stone-900
val KidSurface = Color(0xFF292524)       // stone-800
val KidAccent = Color(0xFF22A559)        // forest green
val KidText = Color(0xFFF5F5F4)          // stone-100
val KidTextDim = Color(0xFFA8A29E)       // stone-400
val KidFocusRing = Color(0x9922A559)     // green 60%
// A focus ring for a control that sits *on* the accent fill, where `KidFocusRing` cannot work at all: that
// one is the accent at 60%, so over an opaque accent disc it composites to the fill's own colour and
// vanishes - measured at 1.00:1 against the fill on the player's Play/Pause control, i.e. identical pixels
// and no indicator at all (W13.3). A ring that has to be visible on the accent must be a different colour,
// and it needs the dark halo underneath so bright video cannot swallow it.
val KidFocusRingBright = Color(0xFFF5F5F4)   // stone-100
val KidFocusRingHalo = Color(0xCC1C1917)     // stone-900, 80%

// Parent palette (ConnectScreen, SettingsScreen, Dashboard)
val ParentBackground = Color(0xFFFAFAFA) // off-white
val ParentSurface = Color(0xFFFFFFFF)    // white
val ParentAccent = Color(0xFF22A559)     // forest green (buttons, icons, borders)
val ParentAccentText = Color(0xFF15803D) // green-700 (text on light bg, WCAG AA)
val ParentText = Color(0xFF1A1A1A)       // near-black
val ParentTextDim = Color(0xFF4B5563)    // gray-600 (was gray-500, too light)
val ParentBorder = Color(0xFFE5E7EB)     // gray-200

// Shared semantic colors
val StatusError = Color(0xFFDC2626)      // red-600
val StatusWarning = Color(0xFFD97706)    // amber-600
val StatusSuccess = Color(0xFF16A34A)    // green-600

val OverscanPadding = 48.dp
