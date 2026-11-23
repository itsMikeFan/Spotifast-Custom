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

    /** How this phone shows in Spotify Connect. */
    val deviceName = "Spotifast Custom (${Build.MODEL})"

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
        scope.launch {
            while (true) {
                state.value = withContext(Dispatchers.Default) { parse(Core.state()) }
                delay(500)
            }
        }
    }

    fun setEffects(e: Effects, save: Boolean = true) {
        effects.value = e
        Core.setEffects(e.on, e.ratePercent, e.bassDb, e.reverbMix, e.reverbRoom)
        if (save) app.getSharedPreferences("effects", Context.MODE_PRIVATE).edit()
            .putBoolean("on", e.on).putInt("rate", e.ratePercent).putFloat("bass", e.bassDb)
            .putFloat("mix", e.reverbMix).putFloat("room", e.reverbRoom).apply()
    }

    /** Connects with [token], or with the saved playback sign-in. Returns why it failed, or null. */
    suspend fun connect(token: String? = null): String? = connecting.withLock {
        if (token == null && state.value.connected) return null
        withContext(Dispatchers.IO) {
            Core.connect(out, cacheDir, token, deviceName)
        }.also { if (it == null) state.value = state.value.copy(connected = true) }
    }

    /** Plays [track] within [context], starting the background service first. */
    fun play(context: String, track: String?, onError: (String) -> Unit) = load(context, track, -1, onError)

    /** Plays a whole playlist from the top, or shuffled. */
    fun playShuffled(context: String, shuffle: Boolean, onError: (String) -> Unit) = load(context, null, if (shuffle) 1 else 0, onError)

    private fun load(context: String, track: String?, shuffle: Int, onError: (String) -> Unit) {
        ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
        scope.launch {
            connect()?.let { onError(it); return@launch }
            withContext(Dispatchers.IO) { Core.load(context, track, shuffle) }
        }
    }

    /** True while another device plays and this phone doesn't; the library's poll keeps it current. */
    @Volatile var remoteActive = false

    /** Queues on this phone, or on the device that is playing when that is another one. */
    fun queue(uri: String, onError: (String) -> Unit) {
        scope.launch {
            if (remoteActive && !state.value.playing) {
                runCatching { Spotify.queueRemote(uri) }.onFailure { onError("Could not queue it: ${it.message}") }
                return@launch
            }
            connect()?.let { onError(it); return@launch }
            withContext(Dispatchers.IO) { Core.queue(uri) }
        }
    }

    /** Sends a command, reconnecting first if the session dropped. */
    fun command(command: Int, value: Int = 0) {
        scope.launch {
            if (connect() != null) return@launch
            withContext(Dispatchers.IO) { Core.command(command, value) }
        }
    }

    private fun parse(json: String): PlayState = runCatching {
        val o = JSONObject(json)
        PlayState(
            o.optBoolean("connected"), o.optBoolean("playing"), o.optString("uri"), o.optString("name"),
            o.optString("artists"), o.optInt("duration_ms"), o.optInt("position_ms"),
            o.optJSONArray("next")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            o.optBoolean("shuffle"), o.optInt("repeat"),
        )
    }.getOrDefault(PlayState())
}
