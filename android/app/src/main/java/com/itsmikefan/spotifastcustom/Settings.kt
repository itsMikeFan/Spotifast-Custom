package com.itsmikefan.spotifastcustom

import android.content.Context
import android.os.Build
import com.itsmikefan.spotifastcustom.ui.Themes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/** Everything the Settings screen changes. Names follow the desktop's settings.json where the meaning matches. */
data class AppSettings(
    // Appearance
    val theme: String = Themes.SYSTEM,
    val lightTheme: String = Themes.LIGHT,
    val darkTheme: String = Themes.DARK,
    val accentFromArt: Boolean = true,
    val compactList: Boolean = false,
    val libraryGrid: Boolean = false,
    // Visualizer
    val playerBarVis: String = VIS_SPECTRUM,
    /** ARGB, or null for the album art's colours. */
    val playerBarVisColour: Int? = null,
    val visualizerFps: Int = 60,
    val playerBarVisClick: Boolean = true,
    // Playback
    val crossfadeSeconds: Int = 0,
    val smoothSkips: Boolean = true,
    val albumsGapless: Boolean = true,
    val gapless: Boolean = true,
    val normalisation: Boolean = false,
    val bitrate: Int = 320,
    val autoplay: Boolean = true,
    val deviceName: String = "Spotifast Custom (${Build.MODEL})",
    // Sound
    val volume: Int = 100,
    val volumeKeysApp: Boolean = false,
    // Updates
    val checkForUpdates: Boolean = true,
    val searchHistory: List<String> = emptyList(),
) {
    companion object {
        const val VIS_SPECTRUM = "spectrum"
        const val VIS_WAVEFORM = "waveform"
        const val VIS_OFF = "off"
    }
}

object Settings {
    private lateinit var prefs: android.content.SharedPreferences
    private val flow = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> get() = flow
    val value get() = flow.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = AppSettings()
        flow.value = with(prefs) {
            AppSettings(
                theme = getString("theme", d.theme)!!,
                lightTheme = getString("light_theme", d.lightTheme)!!,
                darkTheme = getString("dark_theme", d.darkTheme)!!,
                accentFromArt = getBoolean("accent_from_art", d.accentFromArt),
                compactList = getBoolean("tracklist_compact", d.compactList),
                libraryGrid = getBoolean("sidebar_grid", d.libraryGrid),
                playerBarVis = getString("player_bar_vis", d.playerBarVis)!!,
                playerBarVisColour = if (contains("player_bar_vis_colour")) getInt("player_bar_vis_colour", 0) else null,
                visualizerFps = getInt("visualizer_fps", d.visualizerFps),
                playerBarVisClick = getBoolean("player_bar_vis_click", d.playerBarVisClick),
                crossfadeSeconds = getInt("crossfade_seconds", d.crossfadeSeconds),
                smoothSkips = getBoolean("smooth_skips", d.smoothSkips),
                albumsGapless = getBoolean("albums_gapless", d.albumsGapless),
                gapless = getBoolean("gapless", d.gapless),
                normalisation = getBoolean("normalisation", d.normalisation),
                bitrate = getInt("bitrate", d.bitrate),
                autoplay = getBoolean("autoplay", d.autoplay),
                deviceName = getString("device_name", d.deviceName)!!,
                volume = getInt("volume", d.volume),
                volumeKeysApp = getBoolean("volume_keys_app", d.volumeKeysApp),
                checkForUpdates = getBoolean("check_for_updates", d.checkForUpdates),
                searchHistory = runCatching {
                    val a = JSONArray(getString("search_history", "[]"))
                    (0 until a.length()).map { a.getString(it) }
                }.getOrDefault(emptyList()),
            )
        }
    }

    /** Applies and saves a change at once; there is no Save button. */
    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(flow.value)
        flow.value = s
        prefs.edit()
            .putString("theme", s.theme).putString("light_theme", s.lightTheme).putString("dark_theme", s.darkTheme)
            .putBoolean("accent_from_art", s.accentFromArt).putBoolean("tracklist_compact", s.compactList)
            .putBoolean("sidebar_grid", s.libraryGrid).putString("player_bar_vis", s.playerBarVis)
            .apply { if (s.playerBarVisColour != null) putInt("player_bar_vis_colour", s.playerBarVisColour) else remove("player_bar_vis_colour") }
            .putInt("visualizer_fps", s.visualizerFps).putBoolean("player_bar_vis_click", s.playerBarVisClick)
            .putInt("crossfade_seconds", s.crossfadeSeconds).putBoolean("smooth_skips", s.smoothSkips)
            .putBoolean("albums_gapless", s.albumsGapless).putBoolean("gapless", s.gapless)
            .putBoolean("normalisation", s.normalisation).putInt("bitrate", s.bitrate).putBoolean("autoplay", s.autoplay)
            .putString("device_name", s.deviceName).putInt("volume", s.volume).putBoolean("volume_keys_app", s.volumeKeysApp)
            .putBoolean("check_for_updates", s.checkForUpdates)
            .putString("search_history", JSONArray(s.searchHistory).toString())
            .apply()
    }

    /** Keeps the ten newest searches, newest first, as the desktop's search history does. */
    fun rememberSearch(query: String) = update { s ->
        s.copy(searchHistory = (listOf(query) + s.searchHistory.filter { !it.equals(query, ignoreCase = true) }).take(10))
    }
}
