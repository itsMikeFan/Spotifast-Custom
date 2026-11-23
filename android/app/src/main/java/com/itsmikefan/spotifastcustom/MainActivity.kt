package com.itsmikefan.spotifastcustom

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import org.json.JSONObject
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF1ED760), onPrimary = Color.Black)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) { Root() }
                }
            }
        }
    }
}

@Composable
private fun Root() {
    var signedIn by remember { mutableStateOf(Spotify.signedIn && Playback.hasPlaybackSignIn) }
    if (!signedIn) SignIn { signedIn = true } else Library { Spotify.signOut(); Playback.forgetPlaybackSignIn(); signedIn = false }
    UpdatePrompt()
}

/** Offers a newer build from GitHub once per launch; installing it keeps the sign-in and settings. */
@Composable
private fun UpdatePrompt() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Updates.Update?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { update = Updates.check(context) }
    val found = update ?: return
    AlertDialog(
        onDismissRequest = { if (!busy) update = null },
        title = { Text("Update available") },
        text = { Text(error ?: if (busy) "Downloading build ${found.build}…" else "Build ${found.build} is out. Install it now? Your sign-in and settings stay.") },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { Updates.install(context, found) }
                        .onSuccess { update = null }
                        .onFailure { error = "Could not download it: ${it.message}" }
                    busy = false
                }
            }) { Text(if (error != null) "Try again" else "Update") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { update = null }) { Text("Later") } },
    )
}

@Composable
private fun SignIn(done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf(Spotify.clientId) }
    var webDone by remember { mutableStateOf(Spotify.signedIn) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Spotifast Custom", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
        if (!webDone) {
            Text("Step 1 of 2: sign in with your own Spotify app, the same Client ID the desktop app uses.")
            Text(
                "Its Redirect URIs on developer.spotify.com must include http://127.0.0.1:${Spotify.WEB_PORT}/login",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && clientId.isNotBlank(), onClick = {
                busy = true; error = null
                Spotify.clientId = clientId
                scope.launch {
                    runCatching { Spotify.signIn(context) }
                        // Asked again for new permissions: playback is already allowed.
                        .onSuccess { if (Playback.hasPlaybackSignIn) done() else webDone = true }
                        .onFailure { error = it.message }
                    busy = false
                }
            }) { Text("Sign in to Spotify") }
        } else {
            Text("Step 2 of 2: allow playback on this phone. Spotify asks once; after that it stays signed in.")
            Button(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { Playback.connect(Spotify.playbackToken(context)) }
                        .onSuccess { failed -> if (failed == null) done() else error = failed }
                        .onFailure { error = it.message }
                    busy = false
                }
            }) { Text("Allow playback") }
        }
        if (busy) Text("Finish in the browser, then come back here.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Library(signOut: () -> Unit) {
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var open by remember { mutableStateOf<Playlist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var effects by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<Remote?>(null) }
    val local by Playback.state.collectAsState()
    LaunchedEffect(Unit) {
        runCatching { Spotify.playlists() }.onSuccess { playlists = it }.onFailure { error = it.message }
    }
    // What plays on another device, while this phone is not playing.
    LaunchedEffect(Unit) {
        while (true) {
            remote = if (Playback.state.value.playing) null
            else runCatching { Spotify.remote() }.getOrNull()?.takeIf { it.device != Playback.deviceName }
            delay(3000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (open != null) TextButton(onClick = { open = null }) { Text("‹ Back") }
            Text(open?.name ?: "Your library", fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { devices = true }) { Text("Devices") }
            TextButton(onClick = { effects = true }) { Text("Effects") }
            if (open == null) TextButton(onClick = signOut) { Text("Sign out") }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            when (val playlist = open) {
                null -> playlists?.let { list ->
                    LazyColumn {
                        items(list) { p ->
                            Row(Modifier.fillMaxWidth().clickable { open = p }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Cover(p.cover)
                                Text(p.name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.count >= 0) Text("${p.count}", color = Color.Gray)
                            }
                        }
                    }
                } ?: Loading()
                else -> {
                    BackHandler { open = null }
                    Tracks(
                        playlist, playlists.orEmpty().filter { it.owned && it.id != playlist.id },
                        // Kept in the list too, or reopening the playlist would show the old setting.
                        onPublic = { value -> playlists = playlists?.map { if (it.id == playlist.id) it.copy(public = value) else it } },
                    ) { error = it }
                }
            }
        }
        val elsewhere = remote
        if (elsewhere != null && !local.playing) RemoteBar(elsewhere) { remote = it } else NowPlaying()
    }
    if (effects) EffectsDialog { effects = false }
    if (devices) DevicesDialog { devices = false }
}

@Composable
private fun Loading() = Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
    CircularProgressIndicator()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tracks(playlist: Playlist, targets: List<Playlist>, onPublic: (Boolean) -> Unit, onError: (String) -> Unit) {
    var tracks by remember(playlist.id) { mutableStateOf<List<Track>?>(null) }
    var complete by remember(playlist.id) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<Track?>(null) }
    var adding by remember { mutableStateOf<Track?>(null) }
    var finding by remember { mutableStateOf(false) }
    var unavailableOnly by remember(playlist.id) { mutableStateOf(false) }
    var public by remember(playlist.id) { mutableStateOf(playlist.public) }
    val playing by Playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(playlist.id, reload) {
        complete = false
        runCatching { Spotify.tracks(playlist) { tracks = it } }.onSuccess { tracks = it; complete = true }.onFailure { onError(it.message ?: "Could not load songs") }
    }
    val list = tracks ?: return Loading()
    val unavailable = list.count { !it.playable }
    Column {
        if (playlist.owned || unavailable > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (!playlist.owned) "" else if (public == false) "Private" else "Public", color = Color.Gray, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                // As on the desktop: shows only the songs Spotify says this account can't play.
                if (unavailable > 0 || unavailableOnly) TextButton(onClick = { unavailableOnly = !unavailableOnly }) {
                    Text(if (unavailableOnly) "Show all" else "Unavailable ($unavailable)")
                }
                if (playlist.owned) {
                TextButton(onClick = {
                    val was = public
                    val now = was == false
                    public = now
                    onPublic(now)
                    scope.launch {
                        runCatching { Spotify.setPublic(playlist, now) }.onFailure {
                            public = was
                            was?.let(onPublic)
                            onError("Could not make it ${if (now) "public" else "private"}: ${it.message}")
                        }
                    }
                }) { Text(if (public == false) "Make public" else "Make private") }
                // It needs every song, so it waits for the rest to load.
                TextButton(onClick = { finding = true }, enabled = complete) { Text("Find duplicates") }
                }
            }
            HorizontalDivider()
        }
        LazyColumn {
            items(if (unavailableOnly) list.filter { !it.playable } else list) { t ->
                val current = t.uri == playing.uri
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { Playback.play(playlist.uri, t.uri, onError) },
                                onLongClick = { menuFor = t },
                            )
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.alpha(if (t.playable) 1f else 0.35f)) { Cover(t.cover) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                t.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = when { current -> MaterialTheme.colorScheme.primary; !t.playable -> Color.Gray; else -> Color.Unspecified },
                            )
                            Text(
                                if (t.playable) t.artists else "Unavailable · ${t.artists}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 13.sp,
                            )
                        }
                        // The same menu as a long press, easier to find.
                        TextButton(onClick = { menuFor = t }) { Text("⋮", fontSize = 20.sp, color = Color.Gray) }
                    }
                    DropdownMenu(expanded = menuFor === t, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menuFor = null; Playback.queue(t.uri) })
                        if (targets.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { menuFor = null; adding = t })
                        }
                    }
                }
            }
        }
    }
    adding?.let { song -> AddToPlaylist(song, targets, onError) { adding = null } }
    if (finding) FindDuplicates(playlist, list, onError, close = { finding = false }) { reload++ }
}

/** Picks one of the listener's playlists and adds [song], warning first if it is already there. */
@Composable
private fun AddToPlaylist(song: Track, targets: List<Playlist>, onError: (String) -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<Pair<Playlist, Track>?>(null) }
    fun add(target: Playlist) = scope.launch {
        runCatching { Spotify.add(target, listOf(song.uri)) }.onFailure { onError(it.message ?: "Could not add it") }
        close()
    }
    val dup = duplicate
    if (dup != null) {
        val (target, existing) = dup
        AlertDialog(
            onDismissRequest = close,
            title = { Text("Already in ${target.name}") },
            text = { Text("\"${existing.name}\" by ${existing.artists} is already in this playlist. Add \"${song.name}\" anyway?") },
            confirmButton = { TextButton(onClick = { add(target) }) { Text("Add anyway") } },
            dismissButton = { TextButton(onClick = close) { Text("Don't add") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Add to playlist") },
        text = {
            if (checking) Loading() else LazyColumn {
                items(targets) { target ->
                    Text(
                        target.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable {
                            checking = true
                            scope.launch {
                                val key = Dupes.key(song)
                                runCatching { Spotify.tracks(target) }
                                    .onSuccess { songs ->
                                        val existing = songs.firstOrNull { Dupes.key(it) == key }
                                        if (existing != null) duplicate = target to existing else add(target)
                                    }
                                    .onFailure { onError(it.message ?: "Could not read that playlist"); close() }
                                checking = false
                            }
                        }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** Lists songs in [playlist] more than once; the copies after the first start ticked for removal. */
@Composable
private fun FindDuplicates(playlist: Playlist, tracks: List<Track>, onError: (String) -> Unit, close: () -> Unit, changed: () -> Unit) {
    val groups = remember(tracks) { Dupes.groups(tracks) }
    var remove by remember(tracks) { mutableStateOf(groups.flatMap { it.drop(1) }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(if (groups.isEmpty()) "No duplicates" else "Duplicates") },
        text = {
            if (groups.isEmpty()) Text("Every song in ${playlist.name} is there once.")
            else LazyColumn {
                groups.forEach { group ->
                    item { HorizontalDivider() }
                    items(group) { index ->
                        val t = tracks[index]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(index in remove, { remove = if (it) remove + index else remove - index }, enabled = !busy)
                            Column {
                                Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.artists} · #${index + 1}", maxLines = 1, color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (groups.isNotEmpty()) TextButton(enabled = !busy && remove.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    val (uris, readd) = Dupes.plan(tracks.map { it.uri }, remove)
                    runCatching {
                        Spotify.remove(playlist, uris)
                        for ((uri, position) in readd) Spotify.add(playlist, listOf(uri), position)
                    }.onFailure { onError(it.message ?: "Could not remove them") }
                    changed()
                    close()
                }
            }) { Text(if (busy) "Removing…" else "Remove ${remove.size}") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("Close") } },
    )
}

@Composable
private fun NowPlaying() {
    val state by Playback.state.collectAsState()
    if (state.uri.isEmpty()) return
    var dragging by remember { mutableStateOf<Float?>(null) }
    var upNext by remember { mutableStateOf(false) }
    // Names its cover when the song came from Spotify's queue; the 500 ms poll redraws it.
    LaunchedEffect(state.uri) { Spotify.lookup(state.uri) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(Spotify.known[state.uri]?.cover)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                Text(state.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 13.sp)
            }
            TextButton(onClick = { upNext = true }) { Text("Up next") }
        }
        Bars(state.playing)
        Slider(
            value = dragging ?: state.positionMs.toFloat(),
            valueRange = 0f..maxOf(1, state.durationMs).toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { Playback.command(Core.SEEK, it.toInt()) }; dragging = null },
        )
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            Text(time(state.positionMs), color = Color.Gray, fontSize = 12.sp)
            TextButton(onClick = { Playback.command(Core.PREVIOUS) }) { Text("⏮", fontSize = 24.sp) }
            TextButton(onClick = { Playback.command(if (state.playing) Core.PAUSE else Core.PLAY) }) {
                Text(if (state.playing) "⏸" else "▶", fontSize = 28.sp)
            }
            TextButton(onClick = { Playback.command(Core.NEXT) }) { Text("⏭", fontSize = 24.sp) }
            Text(time(state.durationMs), color = Color.Gray, fontSize = 12.sp)
        }
    }
    if (upNext) UpNext(state.next) { upNext = false }
}

/** The songs after the current one, named even when they came from Spotify's own queue. */
@Composable
private fun UpNext(uris: List<String>, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Up next") },
        text = {
            if (uris.isEmpty()) Text("Nothing after this song.")
            // Full width, so a long title scrolling into view doesn't resize the box.
            else LazyColumn(Modifier.fillMaxWidth()) {
                items(uris) { uri ->
                    var t by remember(uri) { mutableStateOf(Spotify.known[uri]) }
                    // Songs queued in the Spotify app come over as bare URIs.
                    var looked by remember(uri) { mutableStateOf(false) }
                    if (t == null) LaunchedEffect(uri) { t = Spotify.lookup(uri); looked = true }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(t?.cover)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(t?.name ?: if (looked) uri else "…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            t?.let { s -> Text(s.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 12.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done") } },
    )
}

@Composable
private fun Cover(url: String?) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(Color.DarkGray),
    )
}

/** Controls for what plays on another device, through the Web API. */
@Composable
private fun RemoteBar(remote: Remote, changed: (Remote) -> Unit) {
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<String>?>(null) }
    fun send(command: Int) = scope.launch {
        runCatching { Spotify.remoteCommand(command) }
        delay(500)
        runCatching { Spotify.remote() }.getOrNull()?.let(changed)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(remote.cover)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(remote.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                Text(remote.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 13.sp)
                Text("Playing on ${remote.device}", maxLines = 1, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
            TextButton(onClick = {
                scope.launch { queue = runCatching { Spotify.remoteQueue() }.getOrDefault(emptyList()) }
            }) { Text("Up next") }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            TextButton(onClick = { send(Core.PREVIOUS) }) { Text("⏮", fontSize = 24.sp) }
            TextButton(onClick = { changed(remote.copy(playing = !remote.playing)); send(if (remote.playing) Core.PAUSE else Core.PLAY) }) {
                Text(if (remote.playing) "⏸" else "▶", fontSize = 28.sp)
            }
            TextButton(onClick = { send(Core.NEXT) }) { Text("⏭", fontSize = 24.sp) }
        }
    }
    queue?.let { UpNext(it) { queue = null } }
}

/** Spotify Connect devices, plus Connect and Cast speakers found on the network; tapping one moves playback there. */
@Composable
private fun DevicesDialog(close: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Play on") },
        text = {
            Column {
                status?.let { Text(it, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp)) }
                if (devices == null) Loading() else LazyColumn {
                    items(listed) { d ->
                        val here = d.name == Playback.deviceName
                        Column(Modifier.fillMaxWidth().clickable {
                            if (here) scope.launch { Playback.connect()?.let { status = it } ?: move(d.id, d.name) } else move(d.id, d.name)
                        }.padding(vertical = 10.dp)) {
                            Text(if (here) "This phone" else d.name, color = if (d.active) MaterialTheme.colorScheme.primary else Color.Unspecified)
                            Text(d.type + if (d.active) " · playing" else "", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    if (listed.none { it.name == Playback.deviceName }) item {
                        Column(Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                Playback.connect()?.let { status = it; return@launch }
                                delay(1500)
                                runCatching { Spotify.devices() }.getOrNull()?.firstOrNull { it.name == Playback.deviceName }
                                    ?.let { move(it.id, "this phone") } ?: run { status = "This phone is ready. Pick it again in a moment." }
                            }
                        }.padding(vertical = 10.dp)) {
                            Text("This phone")
                            Text("Spotifast", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    items(extra) { row ->
                        Column(Modifier.fillMaxWidth().clickable { start(row) }.padding(vertical = 10.dp)) {
                            Text(row.optString("name"))
                            Text(if (row.optBoolean("cast")) "Google Cast" else "Spotify Connect on this network", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    if (searching) item { Text("Looking for speakers on this network…", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 10.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

/** The visualizer: bars from the core's spectrum, about 30 times a second while playing. */
@Composable
private fun Bars(playing: Boolean) {
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
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(36.dp).padding(vertical = 4.dp)) {
        if (levels.isEmpty()) return@Canvas
        val slot = size.width / levels.size
        levels.forEachIndexed { i, level ->
            val h = size.height * level.coerceIn(0.02f, 1f)
            drawRect(color, topLeft = Offset(i * slot + slot * 0.15f, size.height - h), size = Size(slot * 0.7f, h))
        }
    }
}

private fun time(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
private fun EffectsDialog(close: () -> Unit) {
    val e by Playback.effects.collectAsState()
    AlertDialog(
        onDismissRequest = close,
        confirmButton = { TextButton(onClick = close) { Text("Done") } },
        dismissButton = { TextButton(onClick = { Playback.setEffects(Effects(on = e.on)) }) { Text("Reset") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Effects", Modifier.weight(1f))
                Switch(e.on, { Playback.setEffects(e.copy(on = it)) })
            }
        },
        text = {
            Column {
                Labelled("Rate ${e.ratePercent}%  (slower is lower, like a turntable)") {
                    Slider(e.ratePercent.toFloat(), { Playback.setEffects(e.copy(ratePercent = Math.round(it))) }, valueRange = 50f..150f, enabled = e.on)
                }
                Labelled("Bass ${"%+.1f".format(e.bassDb)} dB") {
                    Slider(e.bassDb, { Playback.setEffects(e.copy(bassDb = Math.round(it * 2) / 2f)) }, valueRange = -12f..12f, enabled = e.on)
                }
                Labelled("Reverb ${Math.round(e.reverbMix * 100)}%") {
                    Slider(e.reverbMix, { Playback.setEffects(e.copy(reverbMix = it)) }, enabled = e.on)
                }
                Labelled("Room size ${Math.round(e.reverbRoom * 100)}%") {
                    Slider(e.reverbRoom, { Playback.setEffects(e.copy(reverbRoom = it)) }, enabled = e.on)
                }
            }
        },
    )
}

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
    Text(label, fontSize = 13.sp)
    content()
}
