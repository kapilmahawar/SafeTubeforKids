package tv.safetubeforkids.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tv.safetubeforkids.app.ui.components.CatalogCard
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim

/**
 * The shelf renderer, shared by every screen that shows catalog content.
 *
 * The home screen draws one shelf per category with Continue Watching first; an open container draws
 * its children as a single shelf. Both use this file, so the card size, the heading style, the focus
 * ring, the focus restoration and the empty states cannot drift apart between the two.
 *
 * Nothing here is a category card: a shelf's title is a `Text` in the header row, and the only
 * focusable things on the screen are the cards themselves.
 */

/**
 * How long the restore keeps asking for the pressed card after coming back, in frames (~1.5 s at TV
 * frame rates): long enough to outlast the destination's enter transition, short enough that the child
 * never notices it.
 */
private const val RESTORE_GUARD_FRAMES = 90

@Composable
internal fun CatalogShelves(
    shelves: List<CatalogShelfUi>,
    onCardSelected: (CatalogCardUi) -> Unit,
    modifier: Modifier = Modifier,
    /** The card to put focus back on when coming back to this screen, if it is still there. */
    restoreCardId: String? = null,
    onRestoreHandled: () -> Unit = {},
) {
    val shelvesState = rememberLazyListState()
    var wanted by remember(restoreCardId) { mutableStateOf(restoreCardId) }
    var pendingFocusId by remember { mutableStateOf<String?>(null) }
    var focusedCardId by remember { mutableStateOf<String?>(null) }
    var keyPressed by remember { mutableStateOf(false) }
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    // Which card, if any, of *these* shelves holds the focus right now. It is what tells the restore
    // below whether it succeeded, whether something else on the screen took the focus, or whether the
    // child has moved on by themselves.
    val onCardFocusChanged: (String, Boolean) -> Unit = { cardId, focused ->
        if (focused) {
            focusedCardId = cardId
        } else if (focusedCardId == cardId) {
            focusedCardId = null
        }
    }

    // Coming back from a container or from the player: bring the pressed card back into view.
    LaunchedEffect(wanted, shelves) {
        val target = wanted ?: return@LaunchedEffect
        val shelfIndex = shelves.indexOfFirst { shelf -> shelf.cards.any { it.id == target } }
        if (shelfIndex < 0) {
            // The item is no longer in the catalog; leave focus to the normal first-press behaviour.
            wanted = null
            onRestoreHandled()
            return@LaunchedEffect
        }
        if (shelvesState.firstVisibleItemIndex != shelfIndex) {
            runCatching { shelvesState.scrollToItem(shelfIndex) }
        }
        keyPressed = false
        pendingFocusId = target
    }

    // ...and then keep it there. Asking once is not enough: while the destination is returning, the
    // focus system hands the initial focus to the first focusable on the screen - the top bar - which
    // happens *after* the card has already taken it, so a single request is silently undone. So the ask
    // is repeated until the card stops being displaced, and it ends immediately when a key is pressed or
    // when a *different* card takes the focus: from that moment on the child is driving.
    //
    // The pressed card is only handed back to the caller when this is over. A screen coming back may be
    // composed more than once (the catalog flow can emit an empty state for a frame while it restarts),
    // and a request consumed by a composition that is thrown away would leave the child on the top bar.
    LaunchedEffect(pendingFocusId) {
        val target = pendingFocusId ?: return@LaunchedEffect
        var frame = 0
        while (frame++ < RESTORE_GUARD_FRAMES) {
            val holder = focusedCardId
            if (keyPressed || (holder != null && holder != target)) break
            if (holder == null) {
                focusRequesters[target]?.let { requester -> runCatching { requester.requestFocus() } }
            }
            withFrameNanos { }
        }
        pendingFocusId = null
        onRestoreHandled()
    }

    LazyColumn(
        state = shelvesState,
        contentPadding = PaddingValues(bottom = 32.dp),
        // The key that moves the focus out of this list still arrives here first, which is how the
        // restore knows the child has taken over. Nothing is consumed: the key does exactly what it did.
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) keyPressed = true
            false
        },
    ) {
        items(shelves, key = { it.id }) { shelf ->
            CatalogShelfSection(
                shelf = shelf,
                focusRequesterFor = { cardId -> focusRequesters.getOrPut(cardId) { FocusRequester() } },
                pendingFocusId = pendingFocusId,
                onCardFocusChanged = onCardFocusChanged,
                onCardClick = onCardSelected,
            )
        }
    }
}

/**
 * One shelf: a heading, and a row of the cards under it.
 *
 * The heading is the category's (or the open container's) own name. It is a `Text`, never a card: a
 * category is a group title, so it is not focusable, not clickable and cannot be "opened" - and, on
 * the home screen, it has no picture either. The only heading that carries one is an open
 * sub-category's screen, where the picture is that sub-category's own thumbnail.
 */
@Composable
private fun CatalogShelfSection(
    shelf: CatalogShelfUi,
    focusRequesterFor: (String) -> FocusRequester,
    pendingFocusId: String?,
    onCardFocusChanged: (String, Boolean) -> Unit,
    onCardClick: (CatalogCardUi) -> Unit,
) {
    val rowState: LazyListState = rememberLazyListState()

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A picture here only ever belongs to an open container. A category's heading is text and
            // nothing else: no image beside it, no icon, no decoration, and nothing here asks the
            // thumbnail resolver for one. Nothing in this row is focusable, so D-pad navigation and
            // the card order are untouched either way.
            HeadingPicture(url = shelf.headingPicture, title = shelf.title)

            Text(
                text = shelf.title,
                style = MaterialTheme.typography.titleMedium,
                color = KidText,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.cards, key = { it.id }) { card ->
                CatalogCard(
                    card = card,
                    onClick = { onCardClick(card) },
                    focusRequester = focusRequesterFor(card.id),
                    requestFocusNow = pendingFocusId == card.id,
                    onFocusRequestHandled = {},
                    onFocusChanged = { focused -> onCardFocusChanged(card.id, focused) },
                )
            }
        }
    }
}

/**
 * The picture beside a heading - an open container's own thumbnail, or nothing at all.
 *
 * `url` is non-null only for a sub-category's screen ([CatalogContainerUi.asShelf]); a category's
 * heading passes null and draws nothing, so the home screen's titles are text only. Never focusable:
 * it is a decoration beside the title, so it cannot take focus away from the cards or change the way
 * the screen is navigated. Coil loads a URL the approved cache already holds.
 */
@Composable
private fun HeadingPicture(url: String?, title: String) {
    if (url.isNullOrBlank()) return

    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(72.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(KidSurface),
    )

    Spacer(modifier = Modifier.width(10.dp))
}

/**
 * Shown when the parent has configured nothing - either a fresh installation, or a catalog that
 * deliberately contains no shelves. No stale content, no YouTube browsing, no spinner: just a calm
 * message a child can read and a parent can act on.
 */
@Composable
internal fun EmptyCatalogState() {
    CenteredMessage(
        title = "No videos yet",
        detail = "Ask a parent to add videos to SafeTube",
    )
}

/**
 * Shown when a container the child opened has nothing visible in it.
 *
 * The child is already inside the container, so a blank screen would look broken: this says what is
 * true instead. It is not a placeholder video card, nothing here is focusable, and pressing Enter has
 * nothing to do - so there is no way to start playing something that does not exist.
 */
@Composable
internal fun EmptyContainerState() {
    CenteredMessage(
        title = "Nothing here yet",
        detail = "There is nothing in this list right now",
    )
}

@Composable
private fun CenteredMessage(title: String, detail: String) {
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
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = KidText,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = KidTextDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}
