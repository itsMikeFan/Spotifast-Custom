package com.itsmikefan.spotifastcustom

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmikefan.spotifastcustom.ui.Icons
import com.itsmikefan.spotifastcustom.ui.LocalPalette
import com.itsmikefan.spotifastcustom.ui.PillButton
import com.itsmikefan.spotifastcustom.ui.Radius
import com.itsmikefan.spotifastcustom.ui.SettingsScreen
import com.itsmikefan.spotifastcustom.ui.SpotifastTheme
import com.itsmikefan.spotifastcustom.ui.chosenPalette
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        setContent {
            val s by Settings.state.collectAsState()
            val palette = chosenPalette(this, s.theme, s.lightTheme, s.darkTheme)
            SpotifastTheme(palette) {
                Surface(Modifier.fillMaxSize(), color = palette.window, contentColor = palette.text) {
                    Column(Modifier.safeDrawingPadding()) { Root() }
                }
            }
        }
    }
}

@Composable
private fun Root() {
    var signedIn by remember { mutableStateOf(Spotify.signedIn && Playback.hasPlaybackSignIn) }
    var offered by remember { mutableStateOf<Updates.Update?>(null) }
    if (!signedIn) SignIn { signedIn = true } else Main(offer = { offered = it }) { Spotify.signOut(); Playback.forgetPlaybackSignIn(); signedIn = false }
    UpdatePrompt(offered) { offered = null }
}

private enum class Tab(val label: String) { Library("Library"), Search("Search"), Settings("Settings") }

/** Library, Search and Settings over the player bar, as the desktop keeps its player bar under every page. */
@Composable
private fun Main(offer: (Updates.Update) -> Unit, signOut: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var full by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var open by remember { mutableStateOf<Playlist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<Remote?>(null) }
    val local by Playback.state.collectAsState()
    val p = LocalPalette.current
    LaunchedEffect(Unit) {
        runCatching { Spotify.playlists() }.onSuccess { playlists = it }.onFailure { error = it.message }
    }
    // What plays on another device, while this phone is not playing.
    LaunchedEffect(Unit) {
        while (true) {
            remote = if (Playback.state.value.playing) null
            else runCatching { Spotify.remote() }.getOrNull()?.takeIf { it.device != Playback.deviceName }
            Playback.remoteActive = remote != null
            kotlinx.coroutines.delay(3000)
        }
    }
    val owned = playlists.orEmpty().filter { it.owned }
    val nav = Nav(
        openPlaylist = { open = it; if (tab == Tab.Settings) tab = Tab.Library },
        sheet = { sheet = it },
        error = { error = it },
        targets = owned,
    )
    BackHandler(full) { full = false }
    BackHandler(!full && open != null) { open = null }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val playlist = open
                when {
                    tab == Tab.Settings -> SettingsScreen(openEffects = { sheet = Sheet.Effects }, signOut = signOut, offerUpdate = offer)
                    playlist != null -> PlaylistScreen(
                        playlist, nav, back = { open = null },
                        // Kept in the list too, or reopening the playlist would show the old setting.
                        onPublic = { value -> playlists = playlists?.map { if (it.id == playlist.id) it.copy(public = value) else it } },
                    )
                    tab == Tab.Library -> LibraryScreen(playlists, nav)
                    else -> SearchScreen(playlists.orEmpty(), nav)
                }
                error?.let { ErrorBanner(it) { error = null } }
            }
            val elsewhere = remote
            if (elsewhere != null && !local.playing) PlayerBar(remote = elsewhere, onRemote = { remote = it }, nav = nav) { full = true }
            else if (local.uri.isNotEmpty()) PlayerBar(remote = null, onRemote = {}, nav = nav) { full = true }
            NavigationBar(containerColor = p.panel, tonalElevation = 0.dp) {
                for (t in Tab.entries) NavigationBarItem(
                    selected = tab == t && (open == null || t != Tab.Settings),
                    onClick = { if (tab == t) open = null; tab = t },
                    icon = { Icon(when (t) { Tab.Library -> Icons.LibraryBig; Tab.Search -> Icons.Search; Tab.Settings -> Icons.Settings }, null, Modifier.size(22.dp)) },
                    label = { Text(t.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = p.text, selectedTextColor = p.text, indicatorColor = p.surfaceActive,
                        unselectedIconColor = p.dim, unselectedTextColor = p.dim,
                    ),
                )
            }
        }
        AnimatedVisibility(
            full,
            enter = slideInVertically(tween(250)) { it },
            exit = slideOutVertically(tween(250)) { it },
        ) {
            FullPlayer(remote, onRemote = { remote = it }, nav = nav, close = { full = false })
        }
    }
    sheet?.let { ShowSheet(it, nav) { sheet = null } }
}

/** What the screens can ask of the shell around them. */
class Nav(
    val openPlaylist: (Playlist) -> Unit,
    val sheet: (Sheet) -> Unit,
    val error: (String) -> Unit,
    /** The listener's own playlists, for Add to playlist. */
    val targets: List<Playlist>,
)

@Composable
private fun ErrorBanner(text: String, dismiss: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(12.dp).clip(Radius).background(p.danger.copy(alpha = 0.16f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = p.danger, fontSize = 13.sp)
        TextButton(onClick = dismiss) { Text("OK", color = p.danger) }
    }
}

@Composable
fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = LocalPalette.current.accent)
}

/** Offers a newer build from GitHub once per launch; installing it keeps the sign-in and settings. */
@Composable
private fun UpdatePrompt(offered: Updates.Update?, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Updates.Update?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val p = LocalPalette.current
    LaunchedEffect(Unit) { if (Settings.value.checkForUpdates) update = Updates.check(context) }
    LaunchedEffect(offered) { if (offered != null) { update = offered; done() } }
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
            }) { Text(if (error != null) "Try again" else "Update", color = p.accent) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { update = null }) { Text("Later", color = p.secondary) } },
    )
}

@Composable
private fun SignIn(done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = LocalPalette.current
    var clientId by remember { mutableStateOf(Spotify.clientId) }
    var webDone by remember { mutableStateOf(Spotify.signedIn) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The desktop's round mark.
            Box(Modifier.size(44.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Play, null, Modifier.size(20.dp).padding(start = 2.dp), tint = p.onAccent)
            }
            Text("Spotifast Custom", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = p.text)
        }
        Column(Modifier.fillMaxWidth().clip(Radius).background(p.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Step(1, "Sign in to Spotify", done = webDone, current = !webDone)
            Step(2, "Allow playback on this phone", done = false, current = webDone)
        }
        if (!webDone) {
            Text("Use your own Spotify app, the same Client ID the desktop app uses.", color = p.secondary)
            Text("Its Redirect URIs on developer.spotify.com must include http://127.0.0.1:${Spotify.WEB_PORT}/login", fontSize = 13.sp, color = p.dim)
            OutlinedTextField(
                clientId, { clientId = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.outline),
            )
            PillButton("Sign in to Spotify", enabled = !busy && clientId.isNotBlank()) {
                busy = true; error = null
                Spotify.clientId = clientId
                scope.launch {
                    runCatching { Spotify.signIn(context) }
                        // Asked again for new permissions: playback is already allowed.
                        .onSuccess { if (Playback.hasPlaybackSignIn) done() else webDone = true }
                        .onFailure { error = it.message }
                    busy = false
                }
            }
        } else {
            Text("Spotify asks once; after that it stays signed in.", color = p.secondary)
            PillButton("Allow playback", enabled = !busy) {
                busy = true; error = null
                scope.launch {
                    runCatching { Playback.connect(Spotify.playbackToken(context)) }
                        .onSuccess { failed -> if (failed == null) done() else error = failed }
                        .onFailure { error = it.message }
                    busy = false
                }
            }
        }
        if (busy) Text("Finish in the browser, then come back here.", color = p.secondary)
        error?.let {
            Text(it, color = p.danger, modifier = Modifier.fillMaxWidth().clip(Radius).background(p.danger.copy(alpha = 0.14f)).padding(12.dp))
        }
    }
}

@Composable
private fun Step(n: Int, label: String, done: Boolean, current: Boolean) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (done || current) p.accent else p.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Check, null, Modifier.size(14.dp), tint = p.onAccent)
            else Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (current) p.onAccent else p.secondary)
        }
        Text(label, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, color = if (done || current) p.text else p.secondary)
    }
}

fun time(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
