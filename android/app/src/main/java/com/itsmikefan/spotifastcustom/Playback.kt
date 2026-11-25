package com.itsmikefan.spotifastcustom

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class PlayState(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val uri: String = "",
    val name: String = "",
    val artists: String = "",
    val durationMs: Int = 0,
    val positionMs: Int = 0,
    /** Upcoming song URIs: the queue, then the rest of the playlist. */
    val next: List<String> = emptyList(),
    val shuffle: Boolean = false,
    /** 0 off, 1 the playlist, 2 this song. */
    val repeat: Int = 0,
    /** The app's volume, 0 to 100, as the core last reported it. */
    val volume: Int = 100,
    /** Why the last song didn't start, from the player; empty once one plays. */
    val error: String = "",
)

data class Effects(
    val on: Boolean = false,
    val ratePercent: Int = 100,
    val bassDb: Float = 0f,
    val reverbMix: Float = 0f,
    val reverbRoom: Float = 0.5f,
)

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Spotify.init(this)
        Playback.init(this)
    }
}

/** The app's one player: connection to the core, its state, and the effects. */
object Playback {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val state = MutableStateFlow(PlayState())
    val effects = MutableStateFlow(Effects())
    private val connecting = Mutex()
    private val out by lazy { AudioOut() }
    private lateinit var app: Context

    /** How this phone shows in Spotify Connect, as of the last connect. */
    var deviceName = "Spotifast Custom (${Build.MODEL})"
        private set

    private val cacheDir get() = File(app.filesDir, "librespot").path
    /** librespot keeps a reusable playback sign-in here after the first one. */
    val hasPlaybackSignIn get() = File(cacheDir, "credentials.json").exists()

    /** Sign out forgets the playback sign-in too, so signing in again starts clean. */
    fun forgetPlaybackSignIn() = File(cacheDir, "credentials.json").delete()

    fun init(context: Context) {
        app = context.applicationContext
        val p = app.getSharedPreferences("effects", Context.MODE_PRIVATE)
        setEffects(
            Effects(
                p.getBoolean("on", false), p.getInt("rate", 100), p.getFloat("bass", 0f),
                p.getFloat("mix", 0f), p.getFloat("room", 0.5f),
            ),
            save = false,
        )
        Core.setVolume(Settings.value.volume)
        applyPlayerOptions()
        warm()
        scope.launch {
            Settings.state.collect { Core.setTransitions(it.crossfadeSeconds * 1000, it.smoothSkips, it.albumsGapless) }
        }
        scope.launch {
            while (true) {
                val next = withContext(Dispatchers.Default) { parse(Core.state()) }
                // Another Spotify app moved this phone's volume: keep the slider and the saved value with it.
                if (next.connected && next.volume != Settings.value.volume && System.currentTimeMillis() - volumeSetAt > 1500) {
                    Settings.update { it.copy(volume = next.volume) }
                }
                corePlaying = next.playing
                val e = expected
                state.value = if (e != null && next.uri != e.first && next.error.isEmpty() && System.currentTimeMillis() < e.second) {
                    // Keep showing the tapped song until the core catches up.
                    val now = state.value
                    next.copy(uri = now.uri, name = now.name, artists = now.artists, durationMs = now.durationMs, positionMs = 0, playing = true)
                } else {
                    expected = null
                    next
                }
                // Poll quickly while a tapped song is on its way, so its first second shows.
                delay(if (expected != null) 100 else 500)
            }
        }
    }

    @Volatile private var volumeSetAt = 0L
    /** The level Unmute goes back to. */
    private var beforeMute = 100

    /** Sets the app's volume at once; cheap enough to call on every slider move. */
    fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, 100)
        volumeSetAt = System.currentTimeMillis()
        Core.setVolume(v)
        if (v != Settings.value.volume) Settings.update { it.copy(volume = v) }
        state.value = state.value.copy(volume = v)
    }

    fun toggleMute() {
        val now = Settings.value.volume
        if (now > 0) { beforeMute = now; setVolume(0) } else setVolume(beforeMute.takeIf { it > 0 } ?: 50)
    }

    fun setEffects(e: Effects, save: Boolean = true) {
        effects.value = e
        Core.setEffects(e.on, e.ratePercent, e.bassDb, e.reverbMix, e.reverbRoom)
        if (save) app.getSharedPreferences("effects", Context.MODE_PRIVATE).edit()
            .putBoolean("on", e.on).putInt("rate", e.ratePercent).putFloat("bass", e.bassDb)
            .putFloat("mix", e.reverbMix).putFloat("room", e.reverbRoom).apply()
    }

    /** The settings the next connect uses: quality, normalisation, gapless, autoplay and name. */
    private fun applyPlayerOptions() {
        val s = Settings.value
        // Crossfade needs the next song loaded early, which is what gapless does.
        Core.setPlayerOptions(s.bitrate, s.normalisation, s.gapless || s.crossfadeSeconds > 0, s.autoplay, s.songCacheMb)
        deviceName = s.deviceName.ifBlank { "Spotifast Custom (${Build.MODEL})" }
    }

    /** What [restart] would change, to show the Apply button only when needed. */
    var appliedOptions = options()
        private set

    fun options() = Settings.value.let { listOf(it.bitrate, it.normalisation, it.gapless || it.crossfadeSeconds > 0, it.autoplay, it.songCacheMb, it.deviceName) }

    /** Reconnects so the playback settings take effect. Playback stops; returns why it failed, or null. */
    suspend fun restart(): String? {
        applyPlayerOptions()
        return connect(force = true).also { if (it == null) appliedOptions = options() }
    }

    /** Connects with [token], or with the saved playback sign-in. Returns why it failed, or null. */
    suspend fun connect(token: String? = null, force: Boolean = false): String? = connecting.withLock {
        // A session kept open from app start may have dropped since (network change, phone asleep).
        if (token == null && !force && Core.alive()) return null
        // Spotify sometimes drops the first connection mid-handshake; two more tries ride that out.
        var failed: String? = null
        for (attempt in 0..2) {
            if (attempt > 0) kotlinx.coroutines.delay(1500L * attempt)
            failed = withContext(Dispatchers.IO) { Core.connect(out, cacheDir, token, deviceName) } ?: break
        }
        failed.also { if (it == null) state.value = state.value.copy(connected = true) }
    }

    /** The song just tapped and until when to show it before the core reports it. */
    @Volatile private var expected: Pair<String, Long>? = null
    /** Whether the core itself says it plays, unlike [state] while a tapped song is shown early. */
    @Volatile private var corePlaying = false

    /** Shows [uri] as playing at once, from what the lists already know about it. */
    private fun expect(uri: String?) {
        val t = uri?.let { Spotify.known[it] } ?: return
        expected = uri to System.currentTimeMillis() + 5000
        state.value = state.value.copy(uri = uri, name = t.name, artists = t.artists, durationMs = t.durationMs, positionMs = 0, playing = true, error = "")
    }

    /** Opens the playback session ahead of the first song, so a tap doesn't wait for sign-in. */
    fun warm() {
        if (hasPlaybackSignIn && !Core.alive()) scope.launch { connect() }
    }

    /** Plays [track] within [context], starting the background service first. */
    fun play(context: String, track: String?, onError: (String) -> Unit) = load(context, track, -1, onError)

    /** Plays a whole playlist from the top, or shuffled, starting with [first] when the caller knows it. */
    fun playShuffled(context: String, shuffle: Boolean, onError: (String) -> Unit, first: String? = null) = load(context, first, if (shuffle) 1 else 0, onError)

    private fun load(context: String, track: String?, shuffle: Int, onError: (String) -> Unit) {
        ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
        expect(track ?: context.takeIf { it.startsWith("spotify:track:") })
        scope.launch {
            connect()?.let { onError(it); return@launch }
            withContext(Dispatchers.IO) { Core.load(context, track, shuffle) }
            // If the session drops before the song starts, connect again and load it once more.
            repeat(16) {
                delay(500)
                if (corePlaying && expected == null) return@launch
                if (!Core.alive()) {
                    connect()?.let { onError(it); return@launch }
                    withContext(Dispatchers.IO) { Core.load(context, track, shuffle) }
                    return@launch
                }
            }
        }
    }

    /** True while another device plays and this phone doesn't; the library's poll keeps it current. */
    @Volatile var remoteActive = false

    /** Queues on this phone, or on the device that is playing when that is another one. */
    fun queue(uri: String, onError: (String) -> Unit) {
        scope.launch {
            if (remoteActive && !state.value.playing) {
                attempt { Spotify.queueRemote(uri) }.onFailure { onError("Could not queue it: ${it.message}") }
                return@launch
            }
            connect()?.let { onError(it); return@launch }
            withContext(Dispatchers.IO) { Core.queue(uri) }
        }
    }

    /** Sends a command, reconnecting first if the session dropped. */
    /** What plays on another device, as the library's poll last saw it. */
    @Volatile var remote: Remote? = null

    /** Jumps to [uri] in Up next in one step, here or on the device that plays. */
    fun skipTo(uri: String, index: Int, onError: (String) -> Unit) {
        scope.launch {
            val r = remote
            if (remoteActive && !state.value.playing && r != null) {
                // Spotify Connect's "skip to this song", over this phone's session: the songs before it never load.
                val direct = r.deviceId.isNotEmpty() && connect() == null &&
                    withContext(Dispatchers.IO) { Core.remoteSkipTo(r.deviceId, uri) } == null
                if (direct) return@launch
                // The Web API can jump within the playing playlist; a song queued outside it needs the skips.
                attempt {
                    if (r.context.isNotEmpty() && attempt { Spotify.playInContext(r.context, uri) }.isSuccess) return@attempt
                    repeat(index + 1) { Spotify.remoteCommand(Core.NEXT) }
                }.onFailure { onError("Could not skip: ${it.message}") }
                return@launch
            }
            expect(uri)
            connect()?.let { onError(it); return@launch }
            withContext(Dispatchers.IO) { Core.skipTo(uri) }
        }
    }

    fun command(command: Int, value: Int = 0) {
        if (command == Core.NEXT) expect(state.value.next.firstOrNull())
        scope.launch {
            if (connect() != null) return@launch
            withContext(Dispatchers.IO) { Core.command(command, value) }
        }
    }

    private fun parse(json: String): PlayState = attempt {
        val o = JSONObject(json)
        PlayState(
            o.optBoolean("connected"), o.optBoolean("playing"), o.optString("uri"), o.optString("name"),
            o.optString("artists"), o.optInt("duration_ms"), o.optInt("position_ms"),
            o.optJSONArray("next")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            o.optBoolean("shuffle"), o.optInt("repeat"), o.optInt("volume", 100), o.optString("error"),
        )
    }.getOrDefault(PlayState())
}
