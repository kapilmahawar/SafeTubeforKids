package tv.safetubeforkids.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
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
import tv.safetubeforkids.app.data.models.VideoItem
import tv.safetubeforkids.app.ui.theme.KidFocusRing
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.07f else 1f, label = "scale")

    Column(
        modifier = modifier
            .width(300.dp)
            .scale(scale)
            .clip(CardShape)
            .background(if (isFocused) KidSurface.copy(alpha = 0.96f) else KidSurface.copy(alpha = 0.55f))
            .then(
                if (isFocused) Modifier.border(4.dp, KidFocusRing, CardShape)
                else Modifier
            )
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
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
            )
            // Duration badge
            if (video.durationSeconds > 0) {
                Text(
                    text = formatDuration(video.durationSeconds),
                    fontSize = 14.sp,
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
            text = video.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isFocused) KidText else KidTextDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
