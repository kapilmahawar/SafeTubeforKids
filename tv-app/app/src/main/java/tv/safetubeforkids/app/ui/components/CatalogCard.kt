package tv.safetubeforkids.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import tv.safetubeforkids.app.ui.screens.CatalogCardKind
import tv.safetubeforkids.app.ui.screens.CatalogCardUi
import tv.safetubeforkids.app.ui.theme.KidAccent
import tv.safetubeforkids.app.ui.theme.KidFocusRing
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim

/**
 * Card dimensions, deliberately identical to [VideoCard]: the Phase 2/3 baseline is
 * 200.dp wide, 16:9 artwork, 12.dp rounded corners, a 1.05 focus scale and a 4.dp focus ring.
 * The catalog redesign changes how cards are arranged, not how big they are.
 */
private val CardShape = RoundedCornerShape(12.dp)
private val CardWidth = 200.dp
private val ArtworkShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private const val CARD_ASPECT_RATIO = 16f / 9f
private const val FOCUS_SCALE = 1.05f
private val FocusRingWidth = 4.dp

/**
 * A card in a catalog shelf: a parent-configured playlist or video.
 *
 * The title is always [CatalogCardUi.title], which is the parent's own wording. A card whose
 * artwork has not been cached yet keeps its exact size and shows a placeholder, so the shelf never
 * jumps when an image fails to load.
 *
 * Focus is drawn with a ring *and* a scale change, so it is obvious from across a room and never
 * depends on colour alone.
 */
@Composable
fun CatalogCard(
    card: CatalogCardUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    requestFocusNow: Boolean = false,
    onFocusRequestHandled: () -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) FOCUS_SCALE else 1f, label = "catalog-card-scale")

    if (focusRequester != null) {
        LaunchedEffect(requestFocusNow) {
            if (!requestFocusNow) return@LaunchedEffect
            // A card returning from the player may need a frame or two to exist again inside a lazy
            // row, and requesting focus before the node is attached throws. Retry a bounded number
            // of times rather than giving up on the first frame.
            repeat(6) {
                if (runCatching { focusRequester.requestFocus() }.isSuccess) {
                    onFocusRequestHandled()
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
        }
    }

    Column(
        modifier = modifier
            .width(CardWidth)
            .scale(scale)
            .clip(CardShape)
            .background(if (isFocused) KidSurface.copy(alpha = 0.96f) else KidSurface.copy(alpha = 0.55f))
            .then(
                if (isFocused) Modifier.border(FocusRingWidth, KidFocusRing, CardShape)
                else Modifier
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter) {
                    onClick()
                    true
                } else false
            }
    ) {
        Box {
            CardArtwork(card)
            card.badgeText?.let { badge ->
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    color = KidText,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(KidSurface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Text(
            text = card.title,
            style = MaterialTheme.typography.bodySmall,
            color = if (isFocused) KidText else KidTextDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * Artwork, or a placeholder of exactly the same size and shape.
 *
 * Coil loads the URL the approved cache already holds; nothing here asks YouTube for anything. A
 * card with no cached artwork, or one whose image fails, still occupies its full 16:9 box so the
 * shelf layout never shifts.
 */
@Composable
private fun CardArtwork(card: CatalogCardUi) {
    val url = card.thumbnailUrl
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CARD_ASPECT_RATIO)
            .clip(ArtworkShape)
            .background(KidSurface),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            PlaceholderArtwork(card.kind)
        } else {
            AsyncImage(
                model = url,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PlaceholderArtwork(kind: CatalogCardKind) {
    val icon: ImageVector = when (kind) {
        CatalogCardKind.PLAYLIST -> Icons.Rounded.PlaylistPlay
        CatalogCardKind.VIDEO -> Icons.Rounded.PlayCircle
        CatalogCardKind.CONTINUE_WATCHING -> Icons.Rounded.PlayCircle
    }
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = KidAccent,
            modifier = Modifier.size(40.dp),
        )
    }
}
