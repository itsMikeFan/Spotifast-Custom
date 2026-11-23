package com.itsmikefan.spotifastcustom

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmikefan.spotifastcustom.ui.Cover
import com.itsmikefan.spotifastcustom.ui.IconBtn
import com.itsmikefan.spotifastcustom.ui.Icons
import com.itsmikefan.spotifastcustom.ui.LocalPalette
import com.itsmikefan.spotifastcustom.ui.PlayCircle
import com.itsmikefan.spotifastcustom.ui.Radius
import com.itsmikefan.spotifastcustom.ui.rememberArtColours
import com.itsmikefan.spotifastcustom.ui.tintFromArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the player shows, from this phone or from the device that plays. */
private data class Shown(
    val uri: String, val name: String, val artists: String, val cover: String?, val bigCover: String?,
    val playing: Boolean, val positionMs: Int, val durationMs: Int, val device: String?,
)

@Composable
private fun shown(remote: Remote?): Shown {
    val state by Playback.state.collectAsState()
    // Names its cover when the song came from Spotify's queue; the 500 ms poll redraws it.
    LaunchedEffect(state.uri) { if (state.uri.isNotEmpty()) Spotify.lookup(state.uri) }
    return if (remote != null) Shown(remote.uri, remote.name, remote.artists, remote.cover, remote.cover, remote.playing, 0, 0, remote.device)
    else Spotify.known[state.uri].let { t ->
        Shown(state.uri, state.name, state.artists, t?.cover, t?.bigCover ?: t?.cover, state.playing, state.positionMs, state.durationMs, null)
    }
}

/** Play, pause, next or previous here, or through the Web API on the device that plays. */
private fun send(remote: Remote?, command: Int, scope: kotlinx.coroutines.CoroutineScope, onRemote: (Remote) -> Unit) {
    if (remote == null) return Playback.command(command)
    scope.launch {
        runCatching { Spotify.remoteCommand(command) }
        delay(500)
        runCatching { Spotify.remote() }.getOrNull()?.let(onRemote)
    }
}

/** Liked Songs status of the song playing, checked once per song. */
@Composable
private fun LikeButton(uri: String, size: androidx.compose.ui.unit.Dp = 22.dp, nav: Nav) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var liked by remember(uri) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(uri) { if (uri.startsWith("spotify:track:")) liked = runCatching { Spotify.liked(listOf(uri)).firstOrNull() }.getOrNull() }
    val now = liked ?: return
    IconBtn(if (now) Icons.HeartFilled else Icons.Heart, if (now) "Remove from Liked Songs" else "Save to Liked Songs", size, tint = if (now) p.accent else p.secondary) {
        liked = !now
        scope.launch { runCatching { Spotify.setLiked(uri, !now) }.onFailure { liked = now; nav.error("Could not change Liked Songs: ${it.message}") } }
    }
}

/** The bar above the tabs: cover, title, like, devices, play. Tap opens the full player, swipe skips. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerBar(remote: Remote?, onRemote: (Remote) -> Unit, nav: Nav, open: () -> Unit) {
    val p = LocalPalette.current
    val s by Settings.state.collectAsState()
    val now = shown(remote)
    val scope = rememberCoroutineScope()
    var swipe by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxWidth().background(p.panel)) {
        // The progress line along the top edge.
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.surface)) {
            if (now.durationMs > 0) Box(Modifier.fillMaxWidth(now.positionMs.toFloat() / now.durationMs).height(2.dp).background(p.accent))
        }
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            // The visualizer moves behind the bar's words, as on the desktop.
            if (remote == null) Bars(now.playing, Modifier.fillMaxSize().padding(horizontal = 0.dp), alpha = 0.28f)
            Row(
                Modifier.fillMaxSize()
                    .pointerInput(remote) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (swipe < -80.dp.toPx()) send(remote, Core.NEXT, scope, onRemote)
                                else if (swipe > 80.dp.toPx()) send(remote, Core.PREVIOUS, scope, onRemote)
                                swipe = 0f
                            },
                            onDragCancel = { swipe = 0f },
                        ) { _, amount -> swipe += amount }
                    }
                    .combinedClickable(onClick = open, onLongClick = { if (s.playerBarVisClick) nav.sheet(Sheet.Visualizer) })
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(now.cover, 44.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(now.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
                    if (now.device != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Speaker, null, Modifier.size(12.dp), tint = p.accent)
                        Text(" ${now.device}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.accent, fontSize = 12.sp)
                    } else Text(now.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.secondary, fontSize = 13.sp)
                }
                LikeButton(now.uri, nav = nav)
                IconBtn(Icons.Speaker, "Devices", on = now.device != null) { nav.sheet(Sheet.Devices) }
                IconBtn(if (now.playing) Icons.Pause else Icons.Play, if (now.playing) "Pause" else "Play", 26.dp, tint = p.text) {
                    if (remote != null) onRemote(remote.copy(playing = !remote.playing))
                    send(remote, if (now.playing) Core.PAUSE else Core.PLAY, scope, onRemote)
                }
            }
        }
    }
}

/** The whole-screen player, over the tabs. */
@Composable
fun FullPlayer(remote: Remote?, onRemote: (Remote) -> Unit, nav: Nav, close: () -> Unit) {
    val p = LocalPalette.current
    val s by Settings.state.collectAsState()
    val state by Playback.state.collectAsState()
    val now = shown(remote)
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val art = rememberArtColours(if (s.accentFromArt) now.bigCover else null)
    val tint by animateColorAsState(tintFromArt(art, p) ?: p.surface, tween(400), label = "tint")
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, p.window)))
            .clickable(enabled = false) {}.padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.ChevronDown, "Close", 26.dp, tint = p.text, onClick = close)
            Text(
                if (now.device != null) "Playing on ${now.device}" else "Now playing",
                Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconBtn(Icons.EllipsisVertical, "More", tint = p.text) {
                Spotify.known[now.uri]?.let { nav.sheet(Sheet.Song(it)) }
            }
        }
        Spacer(Modifier.weight(0.5f))
        Cover(now.bigCover, 600.dp, Modifier.fillMaxWidth().aspectRatio(1f).clip(Radius))
        if (remote == null) Bars(
            now.playing, Modifier.fillMaxWidth().height(64.dp).padding(vertical = 6.dp).combinedClickableLong { if (s.playerBarVisClick) nav.sheet(Sheet.Visualizer) },
        )
        else Spacer(Modifier.height(64.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(now.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now.artists, fontSize = 15.sp, color = p.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LikeButton(now.uri, 26.dp, nav)
        }
        if (remote == null) {
            Slider(
                value = dragging ?: now.positionMs.toFloat(),
                valueRange = 0f..maxOf(1, now.durationMs).toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { Playback.command(Core.SEEK, it.toInt()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = p.text, activeTrackColor = p.text, inactiveTrackColor = p.surfaceActive),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(time((dragging ?: now.positionMs.toFloat()).toInt()), fontSize = 12.sp, color = p.secondary)
                Spacer(Modifier.weight(1f))
                Text(time(now.durationMs), fontSize = 12.sp, color = p.secondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            IconBtn(Icons.Shuffle, "Shuffle", 24.dp, on = state.shuffle, enabled = remote == null) { Playback.command(Core.SHUFFLE, if (state.shuffle) 0 else 1) }
            IconBtn(Icons.SkipBack, "Previous", 30.dp, tint = p.text) { send(remote, Core.PREVIOUS, scope, onRemote) }
            PlayCircle(now.playing, 68.dp) {
                if (remote != null) onRemote(remote.copy(playing = !remote.playing))
                send(remote, if (now.playing) Core.PAUSE else Core.PLAY, scope, onRemote)
            }
            IconBtn(Icons.SkipForward, "Next", 30.dp, tint = p.text) { send(remote, Core.NEXT, scope, onRemote) }
            IconBtn(if (state.repeat == 2) Icons.Repeat1 else Icons.Repeat, "Repeat", 24.dp, on = state.repeat != 0, enabled = remote == null) {
                Playback.command(Core.REPEAT, (state.repeat + 1) % 3)
            }
        }
        VolumeRow(remote, onRemote)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            IconBtn(Icons.Speaker, "Devices", on = remote != null) { nav.sheet(Sheet.Devices) }
            IconBtn(Icons.AudioLines, "Effects", on = Playback.effects.collectAsState().value.on) { nav.sheet(Sheet.Effects) }
            IconBtn(Icons.ListMusic, "Up next") {
                if (remote == null) nav.sheet(Sheet.UpNext(state.next))
                else scope.launch { nav.sheet(Sheet.UpNext(runCatching { Spotify.remoteQueue() }.getOrDefault(emptyList()))) }
            }
        }
    }
}

/** Filled in by the volume build. */
@Composable
fun VolumeRow(remote: Remote?, onRemote: (Remote) -> Unit) {}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableLong(onLong: () -> Unit) = this.combinedClickable(onClick = {}, onLongClick = onLong)

/** The visualizer: bars from the core's spectrum, about 30 times a second while playing. */
@Composable
fun Bars(playing: Boolean, modifier: Modifier, alpha: Float = 1f) {
    var levels by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(playing) {
        if (!playing) {
            levels = FloatArray(0)
            return@LaunchedEffect
        }
        while (true) {
            val next = withContext(Dispatchers.Default) { Core.bands() }
            // Rise at once, fall gently, like a classic analyser.
            levels = FloatArray(next.size) { i -> maxOf(next[i], (levels.getOrNull(i) ?: 0f) - 0.04f) }
            delay(33)
        }
    }
    val color = LocalPalette.current.accent.copy(alpha = alpha)
    Canvas(modifier) {
        if (levels.isEmpty()) return@Canvas
        val slot = size.width / levels.size
        levels.forEachIndexed { i, level ->
            val h = size.height * level.coerceIn(0.02f, 1f)
            drawRect(color, topLeft = Offset(i * slot + slot * 0.15f, size.height - h), size = Size(slot * 0.7f, h))
        }
    }
}
