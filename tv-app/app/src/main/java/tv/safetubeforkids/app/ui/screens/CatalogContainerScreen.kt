package tv.safetubeforkids.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.safetubeforkids.app.BuildConfig
import tv.safetubeforkids.app.ui.theme.KidBackground
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.OverscanPadding

/**
 * The child-facing screen for one sub-category.
 *
 * A sub-category is a group, so opening it shows the group: the container's own name as the heading and
 * its children as the same cards its shelf would draw - a nested container as a card that opens, a
 * video as a card that plays. Nothing here starts playing on arrival, which is the whole point: the
 * child asked to see what is inside, not to watch the first thing in it.
 *
 * Everything is local. The cards come from the same Room flows the home screen reads, so a container
 * opens with the network off, and nothing on this screen asks YouTube for anything.
 *
 * A category can never be opened here: a category is a shelf *title*, and [CatalogUiProjection.container]
 * answers null for one, so a stray navigation argument shows the calm "not available" state instead of
 * an invented menu.
 */
@Composable
fun CatalogContainerScreen(
    containerId: String,
    onPlayVideo: (videoId: String, playlistId: String, videoIndex: Int) -> Unit,
    onOpenContainer: (containerId: String) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit = {},
    viewModel: CatalogContainerViewModel = viewModel(),
) {
    val state by viewModel.stateFor(containerId).collectAsState()
    var restoreCardId by rememberSaveable { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KidBackground)
            .padding(OverscanPadding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Back is a remote Back as well as this button, and both return to the logical
                    // parent - the shelf this container sits on, or the container that holds it.
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = KidText,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // The app's own name stays in the bar so the child always knows where they are;
                    // the *container's* name is the heading below it, exactly as a shelf title is.
                    Text("SafeTube for Kids", style = MaterialTheme.typography.headlineMedium, color = KidText)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = KidText,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            when {
                state == null -> MissingContainerState()
                state?.isEmpty == true -> EmptyContainerState()
                else -> {
                    val container = state!!
                    CatalogShelves(
                        shelves = listOf(container.asShelf()),
                        onCardSelected = { card ->
                            restoreCardId = card.id
                            when (card.kind) {
                                CatalogCardKind.CONTAINER ->
                                    CatalogNavigation.containerOpenedBy(card)?.let(onOpenContainer)

                                // A video is the player's, and the player authorizes it exactly as it
                                // always has: nothing on this screen grants permission.
                                CatalogCardKind.VIDEO, CatalogCardKind.CONTINUE_WATCHING ->
                                    card.videoId?.takeIf { it.isNotBlank() }?.let { videoId ->
                                        onPlayVideo(videoId, card.playlistId?.takeIf { it.isNotBlank() } ?: videoId, 0)
                                    }
                            }
                        },
                        restoreCardId = restoreCardId,
                        onRestoreHandled = { restoreCardId = null },
                    )
                }
            }
        }

        if (BuildConfig.IS_DEBUG) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}-debug",
                style = MaterialTheme.typography.bodySmall,
                color = KidTextDim,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
    }
}

/**
 * Shown when the container is gone - it was deleted, or the navigation argument never named one.
 *
 * Deliberately not a video list and not a placeholder card: there is nothing to open here, so there is
 * nothing to focus and nothing to press.
 */
@Composable
private fun MissingContainerState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
        ) {
            Text(
                text = "This list is not available",
                style = MaterialTheme.typography.headlineSmall,
                color = KidText,
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = "Press Back to see the other videos",
                style = MaterialTheme.typography.bodyLarge,
                color = KidTextDim,
            )
        }
    }
}
