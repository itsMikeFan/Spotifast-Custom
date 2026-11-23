package com.itsmikefan.spotifastcustom.ui

import com.itsmikefan.spotifastcustom.attempt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmikefan.spotifastcustom.AppSettings
import com.itsmikefan.spotifastcustom.Playback
import com.itsmikefan.spotifastcustom.Settings
import com.itsmikefan.spotifastcustom.Spotify
import com.itsmikefan.spotifastcustom.Updates
import kotlinx.coroutines.launch

/** Settings, in the desktop's section order. Every change applies at once. */
@Composable
fun SettingsScreen(openEffects: () -> Unit, signOut: () -> Unit, offerUpdate: (Updates.Update) -> Unit) {
    val s by Settings.state.collectAsState()
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        attempt {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "Imported"
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            Themes.import(context, name, text)
        }.onSuccess { name -> Settings.update { it.copy(theme = name) } }
            .onFailure { importError = "That file is not a Spotifast theme: ${it.message}" }
    }
    fun shows(vararg words: String) = matches(query, *words)

    Column(Modifier.fillMaxSize().background(p.window)) {
        Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, color = p.text, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
        SearchField(query, "Search settings") { query = it }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (shows("Account", "Sign out", "Client ID")) SectionCard("Account") {
                SettingRow("Signed in as", Spotify.userId.ifEmpty { "Spotify" }) {
                    TextButton(onClick = signOut) { Text("Sign out", color = p.danger) }
                }
                RowDivider()
                SettingRow("Client ID", Spotify.clientId.ifEmpty { "Not set" }.let { if (it.length > 12) it.take(8) + "…" else it })
            }
            PlaybackSection(s, query)
            if (shows("Sound", "Effects", "Volume")) SoundSection(s, openEffects)
            if (shows("Appearance", "Theme", "Colour from album art", "Compact", "Grid")) SectionCard("Appearance") {
                SettingRow("Theme", s.theme, onClick = { picking = "theme" }) { Swatch(chosenPalette(context, s.theme, s.lightTheme, s.darkTheme)) }
                if (s.theme == Themes.SYSTEM) {
                    RowDivider()
                    SettingRow("Light mode theme", s.lightTheme, onClick = { picking = "light" }) { Swatch(Themes.find(context, s.lightTheme) ?: Palette.Light) }
                    RowDivider()
                    SettingRow("Dark mode theme", s.darkTheme, onClick = { picking = "dark" }) { Swatch(Themes.find(context, s.darkTheme) ?: Palette.Dark) }
                }
                RowDivider()
                SettingRow("Import theme…", "Any desktop Spotifast theme file (.json)", onClick = { importer.launch(arrayOf("application/json", "text/*", "*/*")) })
                RowDivider()
                SwitchRow("Colour from album art", "Tint headers, the player and the visualizer with the cover.", s.accentFromArt) { v -> Settings.update { it.copy(accentFromArt = v) } }
                RowDivider()
                SwitchRow("Compact song list", "Smaller rows and covers.", s.compactList) { v -> Settings.update { it.copy(compactList = v) } }
                RowDivider()
                SwitchRow("Library as grid", "Playlists as cover cards.", s.libraryGrid) { v -> Settings.update { it.copy(libraryGrid = v) } }
            }
            VisualizerSection(s, query)
            if (shows("Updates", "Check for updates")) SectionCard("Updates") {
                SwitchRow("Check for updates", "Looks for a newer build on GitHub when the app opens.", s.checkForUpdates) { v -> Settings.update { it.copy(checkForUpdates = v) } }
                RowDivider()
                SettingRow("Check now", updateStatus, onClick = {
                    updateStatus = "Checking…"
                    scope.launch {
                        val found = Updates.check(context)
                        updateStatus = if (found == null) "You have the newest build." else "Build ${found.build} is out."
                        found?.let(offerUpdate)
                    }
                })
            }
            if (shows("About", "Version")) SectionCard("About") {
                SettingRow("Spotifast Custom", "Build ${Updates.installed(context)} · this phone shows as “${Playback.deviceName}”")
                // For bug reports: the player's own account of what went wrong.
                SettingRow("Copy playback log", "The player's recent messages, to paste when something doesn't play", onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotifast playback log", com.itsmikefan.spotifastcustom.Core.log().ifEmpty { "(empty)" }))
                    android.widget.Toast.makeText(context, "Playback log copied", android.widget.Toast.LENGTH_SHORT).show()
                })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    picking?.let { which ->
        ThemePicker(
            current = when (which) { "light" -> s.lightTheme; "dark" -> s.darkTheme; else -> s.theme },
            withSystem = which == "theme",
            close = { picking = null },
        ) { name ->
            Settings.update { when (which) { "light" -> it.copy(lightTheme = name); "dark" -> it.copy(darkTheme = name); else -> it.copy(theme = name) } }
            picking = null
        }
    }
    importError?.let { AlertDialog(onDismissRequest = { importError = null }, confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun PlaybackSection(s: AppSettings, query: String) {
    if (!matches(query, "Playback", "Crossfade", "Smooth skips", "Albums", "Gapless", "Normalise", "Quality", "Autoplay", "Device name")) return
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var restarting by remember { mutableStateOf(false) }
    var restartError by remember { mutableStateOf<String?>(null) }
    SectionCard("Playback") {
        val fade = s.crossfadeSeconds
        SettingRow("Crossfade", if (fade == 0) "Off: songs play back to back" else "Each song blends into the next over $fade s") {
            Text(if (fade == 0) "Off" else "$fade s", fontSize = 13.sp, color = p.secondary)
        }
        androidx.compose.material3.Slider(
            fade.toFloat(), { v -> Settings.update { it.copy(crossfadeSeconds = Math.round(v)) } },
            valueRange = 0f..12f, steps = 11, modifier = Modifier.padding(horizontal = 16.dp),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceActive),
        )
        RowDivider()
        SwitchRow("Smooth skips", "A short fade instead of a hard cut when you skip, pick a song or seek", s.smoothSkips) { v -> Settings.update { it.copy(smoothSkips = v) } }
        RowDivider()
        SwitchRow("Don't crossfade albums", "Songs of the same album stay seamless, for live and mixed albums", s.albumsGapless, enabled = fade > 0) { v -> Settings.update { it.copy(albumsGapless = v) } }
        RowDivider()
        SwitchRow("Gapless", if (fade > 0) "Always on while crossfading" else "No silence between songs", s.gapless || fade > 0, enabled = fade == 0) { v -> Settings.update { it.copy(gapless = v) } }
        RowDivider()
        SwitchRow("Normalise volume", "Songs play at a similar loudness", s.normalisation) { v -> Settings.update { it.copy(normalisation = v) } }
        RowDivider()
        SettingRow("Quality", "Streaming bitrate in kbit/s")
        Row(Modifier.padding(start = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in listOf(96, 160, 320)) Chip("$b", s.bitrate == b) { Settings.update { it.copy(bitrate = b) } }
        }
        RowDivider()
        SwitchRow("Autoplay", "Keep playing similar songs when a playlist ends", s.autoplay) { v -> Settings.update { it.copy(autoplay = v) } }
        RowDivider()
        SettingRow("Device name", "How this phone shows in Spotify Connect")
        OutlinedTextField(
            s.deviceName, { v -> Settings.update { it.copy(deviceName = v.take(60)) } }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        if (Playback.options() != Playback.appliedOptions) {
            RowDivider()
            SettingRow("Some changes need a restart", restartError ?: "Playback stops; press play again afterwards.")
            PillButton(if (restarting) "Restarting…" else "Apply and restart playback", enabled = !restarting, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)) {
                restarting = true
                scope.launch { restartError = Playback.restart(); restarting = false }
            }
        }
    }
}

@Composable
private fun SoundSection(s: AppSettings, openEffects: () -> Unit) {
    val p = LocalPalette.current
    SectionCard("Sound") {
        SettingRow("Volume", "The app's own volume. The phone's volume still applies on top.") {}
        androidx.compose.foundation.layout.Row(Modifier.padding(start = 8.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(com.itsmikefan.spotifastcustom.volumeIcon(s.volume), if (s.volume == 0) "Unmute" else "Mute") { Playback.toggleMute() }
            androidx.compose.material3.Slider(
                s.volume.toFloat(), { Playback.setVolume(Math.round(it)) }, valueRange = 0f..100f, modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceActive),
            )
            Text("${s.volume}%", fontSize = 13.sp, color = p.secondary, modifier = Modifier.padding(start = 8.dp))
        }
        RowDivider()
        SwitchRow(
            "Volume keys control the app",
            if (s.volumeKeysApp) "The phone's volume buttons move this slider. Keep the phone's media volume high."
            else "The phone's volume buttons change the phone's media volume.",
            s.volumeKeysApp,
        ) { v -> Settings.update { it.copy(volumeKeysApp = v) } }
        RowDivider()
        SettingRow("Effects", "Rate, bass, reverb and room size", onClick = openEffects) {
            Icon(Icons.AudioLines, null, Modifier.size(20.dp), tint = LocalPalette.current.secondary)
        }
    }
}

@Composable
private fun VisualizerSection(s: AppSettings, query: String) {
    if (!matches(query, "Visualizer", "Spectrum", "Waveform", "Colour", "Frame rate", "Long-press")) return
    var options by remember { mutableStateOf(false) }
    SectionCard("Visualizer") {
        SettingRow(
            "Style and colour",
            listOf(
                when (s.playerBarVis) { AppSettings.VIS_WAVEFORM -> "Waveform"; AppSettings.VIS_OFF -> "Off"; else -> "Spectrum" },
                if (s.playerBarVisColour == null) "colours from album art" else "custom colour",
            ).joinToString(" · "),
            onClick = { options = true },
        ) { s.playerBarVisColour?.let { Box(Modifier.size(20.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(it))) } }
        RowDivider()
        SettingRow("Frame rate", "${s.visualizerFps} fps", onClick = { Settings.update { it.copy(visualizerFps = if (it.visualizerFps == 60) 30 else 60) } })
        RowDivider()
        SwitchRow("Long-press the player bar for visualizer options", null, s.playerBarVisClick) { v -> Settings.update { it.copy(playerBarVisClick = v) } }
    }
    if (options) androidx.compose.material3.AlertDialog(
        onDismissRequest = { options = false },
        confirmButton = { TextButton(onClick = { options = false }) { Text("Done", color = LocalPalette.current.accent) } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { com.itsmikefan.spotifastcustom.VisualizerOptions() } },
    )
}

/** Rows whose words match the settings search stay; a section with none left disappears. */
fun matches(query: String, vararg words: String) = query.isBlank() || words.any { it.contains(query.trim(), ignoreCase = true) }

/** Three dots of a palette: window, accent, text. */
@Composable
fun Swatch(palette: Palette) {
    Row(
        Modifier.clip(CircleShape).background(palette.window).border(1.dp, palette.outline, CircleShape).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (c in listOf(palette.accent, palette.text, palette.secondary)) Box(Modifier.size(10.dp).clip(CircleShape).background(c))
    }
}

@Composable
private fun ThemePicker(current: String, withSystem: Boolean, close: () -> Unit, pick: (String) -> Unit) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val themes = remember { Themes.all(context) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Theme") },
        text = {
            LazyColumn {
                if (withSystem) item { ThemeRow(Themes.SYSTEM, null, current == Themes.SYSTEM) { pick(Themes.SYSTEM) } }
                items(themes) { t -> ThemeRow(t.name, t.palette, t.name == current) { pick(t.name) } }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close", color = p.accent) } },
    )
}

@Composable
private fun ThemeRow(name: String, palette: Palette?, chosen: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RadiusSmall).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (palette != null) Swatch(palette) else Box(Modifier.size(width = 52.dp, height = 20.dp))
        Text(name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, color = if (chosen) p.accent else p.text)
        if (chosen) Icon(Icons.Check, null, Modifier.size(18.dp), tint = p.accent)
    }
}
