package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.ui.screens.player.NuvioMpvSurfaceView
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * TiViMate-style Live TV guide (B10): category column -> channel list with now/next EPG, and a
 * LIVE video preview pane up top. ONE mpv instance serves the whole guide: it resumes the
 * last-played channel on entry, OK on a row tunes it (loadfile replace), OK on the already-tuned
 * channel expands the same surface to fullscreen in place, BACK collapses. Focus movement only
 * browses (info/EPG) — it never touches the stream. No PlayerScreen navigation, no second mpv
 * init (two live decoders are unstable on weak GPUs and double-dip provider connections, which
 * are often capped at 1).
 */
@Composable
fun LiveGuide(
    account: XtreamAccount,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    selectedTabRequester: FocusRequester? = null,
    viewModel: XtreamLiveGuideViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteLiveIds.collectAsStateWithLifecycle()
    LaunchedEffect(account.id) { viewModel.setAccount(account) }

    // RIGHT from a category must land on the channel list — without this, focus search looks
    // rightward at the (non-focusable) preview pane and jumps up to the tabs instead.
    val channelListFocus = remember { FocusRequester() }
    // focusRestorer with no saved child fails the enter and focus wanders — fall back to row 1.
    val firstChannelFocus = remember { FocusRequester() }

    var mpvView by remember { mutableStateOf<NuvioMpvSurfaceView?>(null) }
    DisposableEffect(Unit) { onDispose { mpvView?.releasePlayer() } }
    BackHandler(enabled = fullscreen) { onFullscreenChange(false) }

    // The player tunes ONLY when the preview channel changes (OK press / last-played restore) —
    // never on focus movement. mpv calls go off-main: they can block on the core lock for
    // seconds while a slow live stream is opening (that blocked a KeyEvent >5s = ANR).
    val previewUrl = uiState.previewChannel?.streamUrl
    LaunchedEffect(previewUrl, mpvView) {
        val view = mpvView ?: return@LaunchedEffect
        val url = previewUrl ?: return@LaunchedEffect
        withContext(Dispatchers.Default) { view.setMediaUsingLoadfile(url, emptyMap()) }
    }

    // Backgrounding: stop burning the decoder on STOP; rejoin the live edge on START (a paused
    // live buffer goes stale, so reload instead of unpause). Off-main for the same lock reason.
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> lifecycleScope.launch(Dispatchers.Default) { mpvView?.setPaused(true) }
                Lifecycle.Event.ON_START -> {
                    val url = uiState.previewChannel?.streamUrl ?: return@LifecycleEventObserver
                    lifecycleScope.launch(Dispatchers.Default) { mpvView?.setMediaUsingLoadfile(url, emptyMap()) }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            // Category column
            LazyColumn(
                modifier = Modifier.width(260.dp).fillMaxHeight().focusRestorer(),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm)
            ) {
                itemsIndexed(uiState.categories, key = { _, it -> it.id }) { index, cat ->
                    GuideCategoryRow(
                        label = cat.name,
                        selected = cat.id == uiState.selectedCategoryId,
                        rightFocus = channelListFocus,
                        // First category routes UP back to the active tab so the tabs stay reachable.
                        upFocus = if (index == 0) selectedTabRequester else null,
                        onFocused = { viewModel.selectCategory(cat.id) }
                    )
                }
            }

            // Info pane + channel list; the video overlay below covers the pane's right slot.
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(PREVIEW_PANE_HEIGHT)) {
                    PreviewInfoPane(
                        channelName = uiState.focusedChannel?.name,
                        epg = uiState.focusedChannel?.let { uiState.epg[it.streamId] },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    Spacer(Modifier.fillMaxHeight().aspectRatio(16f / 9f))
                }

                when {
                    uiState.loadingChannels -> StatusLine("Loading channels…")
                    uiState.error != null -> StatusLine(uiState.error!!)
                    uiState.channels.isEmpty() -> StatusLine("No channels here")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize()
                            .focusRequester(channelListFocus)
                            .focusRestorer(firstChannelFocus),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
                    ) {
                        itemsIndexed(uiState.channels, key = { _, it -> it.contentId }) { index, ch ->
                            val isPlaying = ch.contentId == uiState.previewChannel?.contentId
                            GuideChannelRow(
                                focusRequester = if (index == 0) firstChannelFocus else null,
                                // ▶ marks what the preview player is tuned to.
                                name = (if (isPlaying) "▶ " else "") + ch.name,
                                logo = ch.logo,
                                nowTitle = uiState.epg[ch.streamId]?.now?.title,
                                isFavorite = ch.contentId in favoriteIds,
                                // While fullscreen, focus is locked in place behind the video;
                                // BACK collapses. (ponytail: no fullscreen zap yet — add key
                                // up/down channel switching if requested.)
                                lockFocus = fullscreen,
                                onFocused = { viewModel.onChannelFocused(ch, index) },
                                // OK: tune the preview; OK on the tuned channel: go fullscreen.
                                onClick = {
                                    if (isPlaying) onFullscreenChange(true)
                                    else viewModel.playPreview(ch)
                                },
                                onLongClick = { viewModel.toggleFavorite(ch) }
                            )
                        }
                    }
                }
            }
        }

        // The single reused player surface: pane-sized normally, the whole screen when fullscreen.
        // Same composition slot either way, so the SurfaceView (and mpv) survive the toggle.
        Box(
            modifier = (
                if (fullscreen) Modifier.fillMaxSize()
                else Modifier.align(Alignment.TopEnd).height(PREVIEW_PANE_HEIGHT).aspectRatio(16f / 9f)
                ).background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    NuvioMpvSurfaceView(ctx).apply { ensureInitialized() }.also { mpvView = it }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(NuvioTheme.spacing.xxxl)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun GuideCategoryRow(
    label: String,
    selected: Boolean,
    rightFocus: FocusRequester,
    upFocus: FocusRequester? = null,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(focused) { if (focused) latestOnFocused() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties {
                right = rightFocus
                if (upFocus != null) up = upFocus
            }
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    focused -> NuvioTheme.colors.Primary
                    selected -> NuvioTheme.colors.BackgroundElevated
                    else -> Color.Transparent
                }
            )
            // ponytail: onFocusChanged MUST precede the focus target to observe it. And use
            // clickable (not plain focusable) — plain focusable() didn't reliably take D-pad focus
            // in this LazyColumn, so the category never highlighted and selectCategory never fired
            // (stayed on "All channels"). clickable focuses reliably; Enter selects, and moving
            // focus selects via the LaunchedEffect above. Matches GuideChannelRow.
            .onFocusChanged { focused = it.isFocused }
            .clickable { latestOnFocused() }
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused || selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GuideChannelRow(
    name: String,
    logo: String?,
    nowTitle: String?,
    isFavorite: Boolean,
    lockFocus: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(isFocused) { if (isFocused) latestOnFocused() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.md, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused) NuvioTheme.colors.Primary else NuvioTheme.colors.BackgroundElevated)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusProperties {
                if (lockFocus) {
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
            }
            .onFocusChanged { isFocused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        AsyncImage(
            model = logo,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1
            )
            Text(
                text = nowTitle ?: "No information",
                style = MaterialTheme.typography.bodySmall,
                color = if (isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1
            )
        }
        if (isFavorite) {
            Text("★", style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
        }
    }
}

/** Left side of the preview strip: focused channel's name + now/next EPG (video sits to the right). */
@Composable
private fun PreviewInfoPane(
    channelName: String?,
    epg: GuideEpg?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.Bottom
    ) {
        Text(
            text = channelName ?: "",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        epg?.now?.let { now ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${timeRange(now)}  ${now.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2
            )
        }
        epg?.next?.let { next ->
            Text(
                text = "Next: ${timeRange(next)}  ${next.title}",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = "OK preview · OK again fullscreen · hold OK favorite",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 3
        )
    }
}

private fun timeRange(p: XtreamProgram): String = "${hhmm(p.startMs)}–${hhmm(p.endMs)}"

private fun hhmm(ms: Long): String {
    if (ms <= 0L) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private val PREVIEW_PANE_HEIGHT = 300.dp
