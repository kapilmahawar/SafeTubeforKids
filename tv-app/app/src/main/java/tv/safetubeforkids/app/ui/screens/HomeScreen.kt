package tv.safetubeforkids.app.ui.screens

import android.app.ActivityOptions
import android.os.Build
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.safetubeforkids.app.BuildConfig
import tv.safetubeforkids.app.ServiceLocator
import tv.safetubeforkids.app.timelimits.TimeLimitStatus
import tv.safetubeforkids.app.ui.components.AppCard
import tv.safetubeforkids.app.ui.components.CatalogCard
import tv.safetubeforkids.app.ui.theme.KidBackground
import tv.safetubeforkids.app.ui.theme.KidSurface
import tv.safetubeforkids.app.ui.theme.KidText
import tv.safetubeforkids.app.ui.theme.KidTextDim
import tv.safetubeforkids.app.ui.theme.OverscanPadding

/**
 * The child-facing home screen: the parent's catalog, rendered as shelves.
 *
 * Every shelf, every card and every title comes from the local Room catalog. Nothing on this screen
 * performs a network request, and nothing here decides what may play - a card press hands a video id
 * to the existing player, which authorizes it as it always has.
 */
@Composable
fun HomeScreen(
    onPlayVideo: (videoId: String, playlistId: String, videoIndex: Int) -> Unit,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onLocked: (String) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val catalogState by viewModel.catalogState.collectAsState()
    val kioskState by viewModel.kioskState.collectAsState()
    var showingVideos by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.start()
    }

    // Check time limits periodically
    LaunchedEffect(Unit) {
        while (true) {
            val status = ServiceLocator.timeLimitManager.canPlay()
            if (status is TimeLimitStatus.Blocked) {
                // If kiosk is active with enforceTimeLimitsOnAllApps, revoke third-party app access
                if (ServiceLocator.isKioskManagerInitialized()) {
                    val kiosk = ServiceLocator.kioskManager
                    if (kiosk.isDeviceOwner()) {
                        val config = ServiceLocator.database.kioskDao().getConfig()
                        if (config?.kioskEnabled == true && config.enforceTimeLimitsOnAllApps) {
                            kiosk.enforceTimeLimitExpiry()
                        }
                    }
                }
                onLocked(status.reason.name.lowercase())
                return@LaunchedEffect
            } else {
                // If time is allowed again and kiosk had previously revoked access, restore it
                if (ServiceLocator.isKioskManagerInitialized()) {
                    val kiosk = ServiceLocator.kioskManager
                    if (kiosk.isDeviceOwner()) {
                        val config = ServiceLocator.database.kioskDao().getConfig()
                        if (config?.kioskEnabled == true && config.enforceTimeLimitsOnAllApps) {
                            val whitelisted = ServiceLocator.database.whitelistDao().getWhitelisted()
                            kiosk.restoreAfterTimeLimitExpiry(whitelisted.map { it.packageName })
                        }
                    }
                }
            }
            kotlinx.coroutines.delay(5_000)
        }
    }

    val isKioskHome = kioskState.enabled && kioskState.whitelistedApps.isNotEmpty() && !showingVideos

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KidBackground)
            .padding(OverscanPadding)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showingVideos && kioskState.enabled) {
                    // Back button when in videos view within kiosk mode
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { showingVideos = false },
                            colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = "Back to Apps",
                                tint = KidText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Videos", style = MaterialTheme.typography.headlineMedium, color = KidText)
                    }
                } else {
                    Text("SafeTube for Kids", style = MaterialTheme.typography.headlineMedium, color = KidText)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.refresh() },
                        colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Refresh",
                            tint = KidText,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (!isKioskHome) {
                        Button(
                            onClick = onConnect,
                            colors = ButtonDefaults.buttonColors(containerColor = KidSurface),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhoneAndroid,
                                contentDescription = null,
                                tint = KidText,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Connect Phone", color = KidText)
                        }
                    }
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

            if (isKioskHome) {
                // Kiosk home: apps grid with a "Videos" card
                KioskAppsContent(
                    apps = kioskState.whitelistedApps,
                    onLaunchApp = { packageName ->
                        val context = viewModel.getApplication<android.app.Application>()
                        val intent = ServiceLocator.kioskManager.getLeanbackLaunchIntent(packageName)
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                && ServiceLocator.kioskManager.isDeviceOwner()
                            ) {
                                val options = ActivityOptions.makeBasic()
                                options.setLockTaskEnabled(true)
                                context.startActivity(intent, options.toBundle())
                            } else {
                                context.startActivity(intent)
                            }
                        }
                    },
                    onShowVideos = { showingVideos = true },
                )
            } else {
                CatalogContent(
                    state = catalogState,
                    onCardSelected = { card ->
                        viewModel.onCardSelected(card) { target ->
                            onPlayVideo(target.videoId, target.playlistId, target.startIndex)
                        }
                    },
                )
            }
        }

        // Version overlay
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

@Composable
private fun KioskAppsContent(
    apps: List<WhitelistedApp>,
    onLaunchApp: (String) -> Unit,
    onShowVideos: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Apps",
            style = MaterialTheme.typography.titleLarge,
            color = KidText,
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // "Videos" card — opens the catalog
            item {
                AppCard(
                    appName = "Videos",
                    icon = null,
                    onClick = onShowVideos,
                    leadingIcon = Icons.Rounded.PlayCircle,
                )
            }
            // Whitelisted apps
            items(apps) { app ->
                AppCard(
                    appName = app.displayName,
                    icon = app.icon,
                    onClick = { onLaunchApp(app.packageName) },
                )
            }
        }
    }
}

/**
 * The shelves themselves.
 *
 * Focus is remembered across a trip to the player: the card that was pressed is written down before
 * navigating, and when the screen comes back that card is focused again. If the catalog changed
 * underneath and the card is gone, nothing is forced - the shelf simply takes focus normally.
 */
@Composable
private fun CatalogContent(
    state: CatalogUiState,
    onCardSelected: (CatalogCardUi) -> Unit,
) {
    if (state.isEmpty) {
        EmptyCatalogState()
        return
    }

    val shelvesState = rememberLazyListState()
    var restoreCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFocusId by remember { mutableStateOf<String?>(null) }
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    // Coming back from the player: bring the pressed card back into view and give it focus.
    LaunchedEffect(restoreCardId, state.shelves) {
        val wanted = restoreCardId ?: return@LaunchedEffect
        val shelfIndex = state.shelves.indexOfFirst { shelf -> shelf.cards.any { it.id == wanted } }
        if (shelfIndex < 0) {
            // The item is no longer in the catalog; leave focus to the normal first-press behaviour.
            restoreCardId = null
            return@LaunchedEffect
        }
        if (shelvesState.firstVisibleItemIndex != shelfIndex) {
            runCatching { shelvesState.scrollToItem(shelfIndex) }
        }
        pendingFocusId = wanted
    }

    LazyColumn(
        state = shelvesState,
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(state.shelves, key = { it.id }) { shelf ->
            CatalogShelfSection(
                shelf = shelf,
                focusRequesterFor = { cardId -> focusRequesters.getOrPut(cardId) { FocusRequester() } },
                pendingFocusId = pendingFocusId,
                onFocusRequestHandled = { handled -> if (pendingFocusId == handled) pendingFocusId = null },
                onCardClick = { card ->
                    restoreCardId = card.id
                    onCardSelected(card)
                },
            )
        }
    }
}

@Composable
private fun CatalogShelfSection(
    shelf: CatalogShelfUi,
    focusRequesterFor: (String) -> FocusRequester,
    pendingFocusId: String?,
    onFocusRequestHandled: (String) -> Unit,
    onCardClick: (CatalogCardUi) -> Unit,
) {
    val rowState: LazyListState = rememberLazyListState()

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    onFocusRequestHandled = { onFocusRequestHandled(card.id) },
                )
            }
        }
    }
}

/**
 * Shown when the parent has configured nothing - either a fresh installation, or a catalog that
 * deliberately contains no shelves. No stale content, no YouTube browsing, no spinner: just a calm
 * message a child can read and a parent can act on.
 */
@Composable
private fun EmptyCatalogState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 48.dp),
        ) {
            Text(
                text = "No videos yet",
                style = MaterialTheme.typography.headlineSmall,
                color = KidText,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Ask a parent to add videos to SafeTube",
                style = MaterialTheme.typography.bodyLarge,
                color = KidTextDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}
