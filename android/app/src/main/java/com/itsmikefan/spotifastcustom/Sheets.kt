package com.itsmikefan.spotifastcustom

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmikefan.spotifastcustom.ui.Cover
import com.itsmikefan.spotifastcustom.ui.Icons
import com.itsmikefan.spotifastcustom.ui.LocalPalette
import com.itsmikefan.spotifastcustom.ui.PillButton
import com.itsmikefan.spotifastcustom.ui.RowDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The bottom sheets the screens open. */
sealed class Sheet {
    data class Song(val track: Track) : Sheet()
    data class AddTo(val track: Track) : Sheet()
    data class Duplicates(val playlist: Playlist, val tracks: List<Track>, val changed: () -> Unit) : Sheet()
    data class UpNext(val uris: List<String>) : Sheet()
    data object Devices : Sheet()
    data object Effects : Sheet()
    data object Visualizer : Sheet()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowSheet(sheet: Sheet, nav: Nav, close: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet !is Sheet.Song),
        containerColor = p.overlay, contentColor = p.text,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            when (sheet) {
                is Sheet.Song -> SongSheet(sheet.track, nav, close)
                is Sheet.AddTo -> AddToPlaylist(sheet.track, nav, close)
                is Sheet.Duplicates -> FindDuplicates(sheet.playlist, sheet.tracks, nav, close, sheet.changed)
                is Sheet.UpNext -> UpNext(sheet.uris)
                Sheet.Devices -> Devices(close)
                Sheet.Effects -> EffectsSheet()
                Sheet.Visualizer -> VisualizerSheet()
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = LocalPalette.current.text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = p.secondary)
        Text(label, fontSize = 15.sp, color = p.text)
    }
}

@Composable
private fun SongSheet(t: Track, nav: Nav, close: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var liked by remember(t.uri) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(t.uri) { if (t.uri.startsWith("spotify:track:")) liked = runCatching { Spotify.liked(listOf(t.uri)).firstOrNull() }.getOrNull() }
    Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(t.cover, 52.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(t.name, fontWeight = FontWeight.SemiBold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artists, fontSize = 13.sp, color = p.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(8.dp))
    RowDivider()
    SheetItem(Icons.ListMusic, "Add to queue") { close(); Playback.queue(t.uri, nav.error) }
    if (nav.targets.isNotEmpty()) SheetItem(Icons.Plus, "Add to playlist…") { nav.sheet(Sheet.AddTo(t)) }
    liked?.let { now ->
        SheetItem(if (now) Icons.HeartFilled else Icons.Heart, if (now) "Remove from Liked Songs" else "Save to Liked Songs") {
            close()
            scope.launch { runCatching { Spotify.setLiked(t.uri, !now) }.onFailure { nav.error("Could not change Liked Songs: ${it.message}") } }
        }
    }
    if (t.albumUri.startsWith("spotify:album:")) SheetItem(Icons.Music, "Go to album") {
        close()
        val id = t.albumUri.substringAfterLast(":")
        nav.openPlaylist(Playlist(t.album.ifEmpty { "Album" }, t.albumUri, "/albums/$id/tracks?limit=50&market=from_token", -1, cover = t.cover, owner = t.lead, bigCover = t.bigCover))
    }
    SheetItem(Icons.CopyX, "Copy song link") {
        close()
        val link = "https://open.spotify.com/track/" + t.uri.substringAfterLast(":")
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(t.name, link))
    }
}

/** Picks one of the listener's playlists and adds [song], warning first if it is already there. */
@Composable
private fun AddToPlaylist(song: Track, nav: Nav, close: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<Pair<Playlist, Track>?>(null) }
    fun add(target: Playlist) = scope.launch {
        runCatching { Spotify.add(target, listOf(song.uri)) }.onFailure { nav.error(it.message ?: "Could not add it") }
        close()
    }
    val dup = duplicate
    if (dup != null) {
        val (target, existing) = dup
        AlertDialog(
            onDismissRequest = close,
            title = { Text("Already in ${target.name}") },
            text = { Text("\"${existing.name}\" by ${existing.artists} is already in this playlist. Add \"${song.name}\" anyway?") },
            confirmButton = { TextButton(onClick = { add(target) }) { Text("Add anyway", color = p.accent) } },
            dismissButton = { TextButton(onClick = close) { Text("Don't add", color = p.secondary) } },
        )
    }
    SheetTitle("Add to playlist")
    if (checking) Box(Modifier.fillMaxWidth().height(120.dp)) { Loading() }
    else LazyColumn(Modifier.heightIn(max = 480.dp)) {
        items(nav.targets) { target ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    checking = true
                    scope.launch {
                        val key = Dupes.key(song)
                        runCatching { Spotify.tracks(target) }
                            .onSuccess { songs ->
                                val existing = songs.firstOrNull { Dupes.key(it) == key }
                                if (existing != null) duplicate = target to existing else add(target)
                            }
                            .onFailure { nav.error(it.message ?: "Could not read that playlist"); close() }
                        checking = false
                    }
                }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaylistCover(target, 40.dp)
                Text(target.name, Modifier.padding(start = 12.dp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Lists songs in [playlist] more than once; the copies after the first start ticked for removal. */
@Composable
private fun FindDuplicates(playlist: Playlist, tracks: List<Track>, nav: Nav, close: () -> Unit, changed: () -> Unit) {
    val p = LocalPalette.current
    val groups = remember(tracks) { Dupes.groups(tracks) }
    var remove by remember(tracks) { mutableStateOf(groups.flatMap { it.drop(1) }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SheetTitle(if (groups.isEmpty()) "No duplicates" else "Duplicates")
    if (groups.isEmpty()) {
        Text("Every song in ${playlist.name} is there once.", color = p.secondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        return
    }
    LazyColumn(Modifier.heightIn(max = 460.dp)) {
        groups.forEach { group ->
            item { RowDivider() }
            items(group) { index ->
                val t = tracks[index]
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        index in remove, { remove = if (it) remove + index else remove - index }, enabled = !busy,
                        colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = p.onAccent),
                    )
                    Cover(t.cover, 36.dp)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(t.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.artists} · #${index + 1}", maxLines = 1, color = p.secondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    PillButton(if (busy) "Removing…" else "Remove ${remove.size}", enabled = !busy && remove.isNotEmpty(), modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 0.dp).fillMaxWidth()) {
        busy = true
        scope.launch {
            val (uris, readd) = Dupes.plan(tracks.map { it.uri }, remove)
            runCatching {
                Spotify.remove(playlist, uris)
                for ((uri, position) in readd) Spotify.add(playlist, listOf(uri), position)
            }.onFailure { nav.error(it.message ?: "Could not remove them") }
            changed()
            close()
        }
    }
}

/** The song playing, then the songs after it, named even when they came from Spotify's own queue. */
@Composable
private fun UpNext(uris: List<String>) {
    val p = LocalPalette.current
    val state by Playback.state.collectAsState()
    SheetTitle("Up next")
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        if (state.uri.isNotEmpty() && !Playback.remoteActive) {
            item { Label("Now playing") }
            item { QueueRow(state.uri, current = true) }
        }
        item { Label("Next") }
        if (uris.isEmpty()) item { Text("Nothing after this song.", color = p.secondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        items(uris) { QueueRow(it, current = false) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalPalette.current.secondary, modifier = Modifier.padding(20.dp, 10.dp, 20.dp, 4.dp))
}

@Composable
private fun QueueRow(uri: String, current: Boolean) {
    val p = LocalPalette.current
    var t by remember(uri) { mutableStateOf(Spotify.known[uri]) }
    // Songs queued in the Spotify app come over as bare URIs.
    var looked by remember(uri) { mutableStateOf(false) }
    if (t == null) LaunchedEffect(uri) { t = Spotify.lookup(uri); looked = true }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(t?.cover, 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t?.name ?: if (looked) uri else "…", maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) p.accent else p.text)
            t?.let { s -> Text(s.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.secondary, fontSize = 12.sp) }
        }
    }
}

private fun deviceIcon(type: String) = when (type.lowercase()) {
    "smartphone", "tablet" -> Icons.Smartphone
    "computer" -> Icons.Monitor
    "castvideo", "castaudio", "tv" -> Icons.Cast
    else -> Icons.Speaker
}

/** Spotify Connect devices, plus Connect and Cast speakers found on the network; tapping one moves playback there. */
@Composable
private fun Devices(close: () -> Unit) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<Device>?>(null) }
    var nearby by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { Spotify.devices() }.onSuccess { devices = it }.onFailure { status = it.message; devices = emptyList() }
        val found = withContext(Dispatchers.IO) {
            // Android drops multicast unless an app holds this lock.
            val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            val lock = wifi?.createMulticastLock("spotifast:discovery")?.apply { setReferenceCounted(false); acquire() }
            try { Core.receivers(3000) } finally { lock?.release() }
        }
        runCatching {
            val array = org.json.JSONArray(found ?: "[]")
            nearby = (0 until array.length()).map { array.getJSONObject(it) }
        }.onFailure { status = runCatching { JSONObject(found ?: "").optString("error") }.getOrNull() ?: it.message }
        searching = false
    }
    fun move(id: String, name: String) {
        status = "Moving playback to $name…"
        scope.launch {
            runCatching { Spotify.transfer(id) }
                .onSuccess { close() }
                .onFailure { status = "Could not move playback to $name: ${it.message}" }
        }
    }
    fun start(row: JSONObject) {
        val name = row.optString("name")
        status = "Starting Spotify on $name…"
        scope.launch {
            Playback.connect()?.let { status = it; return@launch }
            val failed = withContext(Dispatchers.IO) { Core.activate(row.toString()) }
            if (failed != null) {
                status = "Could not start $name: $failed"
                return@launch
            }
            // The speaker joins Spotify Connect a moment after it signs in.
            val id = row.optString("device_id")
            repeat(15) {
                val listed = runCatching { Spotify.devices() }.getOrNull().orEmpty()
                listed.firstOrNull { it.id == id || it.name == name }?.let { move(it.id, name); return@launch }
                delay(1000)
            }
            status = "$name started but did not show up in Spotify. Try again in a moment."
        }
    }
    val listed = devices.orEmpty()
    val extra = nearby.filter { row -> listed.none { it.id == row.optString("device_id") || it.name == row.optString("name") } }
    SheetTitle("Play on")
    status?.let { Text(it, fontSize = 13.sp, color = p.secondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
    if (devices == null) Box(Modifier.fillMaxWidth().height(120.dp)) { Loading() } else LazyColumn(Modifier.heightIn(max = 520.dp)) {
        val here = listed.firstOrNull { it.name == Playback.deviceName }
        item {
            DeviceRow(Icons.Smartphone, "This phone", if (here?.active == true) "Playing" else "Spotifast", here?.active == true) {
                scope.launch {
                    Playback.connect()?.let { status = it; return@launch }
                    if (here != null) return@launch move(here.id, "this phone")
                    delay(1500)
                    runCatching { Spotify.devices() }.getOrNull()?.firstOrNull { it.name == Playback.deviceName }
                        ?.let { move(it.id, "this phone") } ?: run { status = "This phone is ready. Pick it again in a moment." }
                }
            }
        }
        items(listed.filter { it.name != Playback.deviceName }) { d ->
            DeviceRow(deviceIcon(d.type), d.name, d.type + if (d.active) " · playing" else "", d.active) { move(d.id, d.name) }
            if (d.active && d.volume >= 0 && d.canSetVolume) DeviceVolume(d.volume)
        }
        items(extra) { row ->
            DeviceRow(
                if (row.optBoolean("cast")) Icons.Cast else Icons.Speaker, row.optString("name"),
                if (row.optBoolean("cast")) "Google Cast" else "Spotify Connect on this network", false,
            ) { start(row) }
        }
        if (searching) item { Text("Looking for speakers on this network…", fontSize = 12.sp, color = p.dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
    }
}

/** The playing device's volume, sent when you let go. */
@Composable
private fun DeviceVolume(start: Int) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf(start.toFloat()) }
    Row(Modifier.fillMaxWidth().padding(start = 60.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(volumeIcon(Math.round(level)), null, Modifier.size(18.dp), tint = p.secondary)
        Slider(
            level, { level = it }, valueRange = 0f..100f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            onValueChangeFinished = { scope.launch { runCatching { Spotify.remoteVolume(Math.round(level)) } } },
        )
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, detail: String, active: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (active) p.accent else p.secondary)
        Column {
            Text(name, color = if (active) p.accent else p.text, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 12.sp, color = p.secondary)
        }
    }
}

@Composable
private fun EffectsSheet() {
    val p = LocalPalette.current
    val e by Playback.effects.collectAsState()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Effects", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.text)
        TextButton(onClick = { Playback.setEffects(Effects(on = e.on)) }) { Text("Reset", color = p.secondary) }
        Switch(
            e.on, { Playback.setEffects(e.copy(on = it)) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent, uncheckedTrackColor = p.surface),
        )
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Labelled("Rate", "${e.ratePercent}%", "Slower is lower, like a turntable") {
            Slider(e.ratePercent.toFloat(), { Playback.setEffects(e.copy(ratePercent = Math.round(it))) }, valueRange = 50f..150f, enabled = e.on)
        }
        Labelled("Bass", "%+.1f dB".format(e.bassDb)) {
            Slider(e.bassDb, { Playback.setEffects(e.copy(bassDb = Math.round(it * 2) / 2f)) }, valueRange = -12f..12f, enabled = e.on)
        }
        Labelled("Reverb", "${Math.round(e.reverbMix * 100)}%") {
            Slider(e.reverbMix, { Playback.setEffects(e.copy(reverbMix = it)) }, enabled = e.on)
        }
        Labelled("Room size", "${Math.round(e.reverbRoom * 100)}%") {
            Slider(e.reverbRoom, { Playback.setEffects(e.copy(reverbRoom = it)) }, enabled = e.on)
        }
    }
}

@Composable
private fun Labelled(label: String, value: String, hint: String? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, fontWeight = FontWeight.Medium, color = p.text, modifier = Modifier.weight(1f))
        Text(value, color = p.secondary, fontSize = 13.sp)
    }
    hint?.let { Text(it, fontSize = 12.sp, color = p.dim) }
    content()
}

/** Filled in by the visualizer build. */
@Composable
private fun VisualizerSheet() {}
