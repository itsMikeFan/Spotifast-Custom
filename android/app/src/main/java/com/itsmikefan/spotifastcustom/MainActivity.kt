package com.itsmikefan.spotifastcustom

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
    if (!signedIn) SignIn { signedIn = true } else Library { Spotify.signOut(); signedIn = false }
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
                        .onSuccess { webDone = true }
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
    LaunchedEffect(Unit) {
        runCatching { Spotify.playlists() }.onSuccess { playlists = it }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (open != null) TextButton(onClick = { open = null }) { Text("‹ Back") }
            Text(open?.name ?: "Your library", fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                            Row(Modifier.fillMaxWidth().clickable { open = p }.padding(16.dp, 14.dp)) {
                                Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.count >= 0) Text("${p.count}", color = Color.Gray)
                            }
                        }
                    }
                } ?: Loading()
                else -> {
                    BackHandler { open = null }
                    Tracks(playlist) { error = it }
                }
            }
        }
        NowPlaying()
    }
    if (effects) EffectsDialog { effects = false }
}

@Composable
private fun Loading() = Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
    CircularProgressIndicator()
}

@Composable
private fun Tracks(playlist: Playlist, onError: (String) -> Unit) {
    var tracks by remember(playlist) { mutableStateOf<List<Track>?>(null) }
    val playing by Playback.state.collectAsState()
    LaunchedEffect(playlist) {
        runCatching { Spotify.tracks(playlist) }.onSuccess { tracks = it }.onFailure { onError(it.message ?: "Could not load songs") }
    }
    val list = tracks ?: return Loading()
    LazyColumn {
        items(list) { t ->
            val current = t.uri == playing.uri
            Column(
                Modifier.fillMaxWidth()
                    .clickable { Playback.play(playlist.uri, t.uri, onError) }
                    .padding(16.dp, 10.dp)
            ) {
                Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified)
                Text(t.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun NowPlaying() {
    val state by Playback.state.collectAsState()
    if (state.uri.isEmpty()) return
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
        Text(state.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, fontSize = 13.sp)
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
