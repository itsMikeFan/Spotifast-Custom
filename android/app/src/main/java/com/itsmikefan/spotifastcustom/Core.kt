package com.itsmikefan.spotifastcustom

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeShaper
import androidx.annotation.Keep

/** The Rust core (android/core): librespot, Spotify Connect and the effects. */
object Core {
    init {
        System.loadLibrary("spotifast_android")
    }

    /** Returns null when connected, or why it failed. A null token reuses the saved playback sign-in. */
    external fun connect(out: AudioOut, cacheDir: String, token: String?, deviceName: String): String?

    /** Plays [context] (a playlist URI or Liked Songs) starting at [track]; [shuffle] -1 keeps it, 0 off, 1 on. */
    external fun load(context: String, track: String?, shuffle: Int)

    external fun command(command: Int, value: Int)

    /** Adds a song to Spotify's queue, after the current one. */
    external fun queue(uri: String)

    /** Plays [uri] from Up next now, dropping the songs before it. */
    external fun skipTo(uri: String)

    /** [count] songs of a playlist from [offset], read through the playback session, as Web API-shaped JSON or {"error": ...}. */
    external fun playlistPage(uri: String, offset: Int, count: Int): String

    /** Spotify Connect and Cast receivers on the network as a JSON array, or {"error": ...}. Blocks for [timeoutMs]. */
    external fun receivers(timeoutMs: Int): String?

    /** Starts Spotify on a receiver row from [receivers] and signs it in; null on success, else why not. */
    external fun activate(receiver: String): String?

    /** The visualizer's 75 bar heights then their 75 peak caps, 0 to 1. */
    external fun bands(): FloatArray

    /** The player's latest log lines (UTC times), for reporting a problem. */
    external fun log(): String

    /** The waveform: [points] values from -1 to 1. */
    external fun scope(points: Int): FloatArray

    /** The app's volume, 0 to 100, applied after the visualizer; Spotify Connect hears of it too. */
    external fun setVolume(percent: Int)

    /** Crossfade length in ms (0 for plain gapless), fades on skips, seamless albums. Live. */
    external fun setTransitions(crossfadeMs: Int, smoothSkips: Boolean, albumsGapless: Boolean)

    /** Used on the next [connect]. */
    external fun setPlayerOptions(bitrate: Int, normalisation: Boolean, gapless: Boolean, autoplay: Boolean, cacheMb: Int)

    external fun setEffects(on: Boolean, ratePercent: Int, bassDb: Float, reverbMix: Float, reverbRoom: Float)

    /** JSON: next (upcoming URIs), connected, playing, uri, name, artists, duration_ms, position_ms. */
    external fun state(): String

    const val PLAY = 0
    const val PAUSE = 1
    const val NEXT = 2
    const val PREVIOUS = 3
    const val SEEK = 4
    /** Value 1 on, 0 off. */
    const val SHUFFLE = 5
    /** Value 0 off, 1 the playlist, 2 this song. */
    const val REPEAT = 6
}

/** Where the core's audio goes. Called from the core's player thread. */
@Keep
class AudioOut {
    private val track: AudioTrack = run {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        // About a quarter second: short enough that a pause or skip feels immediate.
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, 44100 * 2 * 4 / 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    @Keep
    fun start() {}

    @Keep
    fun stop() {
        track.pause()
    }

    /** The last skip's fade-out, played backwards as the next sound's fade-in. */
    private var shaper: VolumeShaper? = null
    /** One already played backwards; kept until the next skip so its volume holds at full. */
    private var spent: VolumeShaper? = null

    /** Drops what the speaker still holds of the old song, after a short fade when [fade]. */
    @Keep
    fun skip(fade: Boolean) {
        if (fade && track.playState == AudioTrack.PLAYSTATE_PLAYING) runCatching {
            spent?.close()
            spent = null
            shaper = track.createVolumeShaper(FADE).also { it.apply(VolumeShaper.Operation.PLAY) }
            Thread.sleep(FADE_MS)
        }
        track.pause()
        track.flush()
    }

    @Keep
    fun write(samples: FloatArray, count: Int) {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
            shaper?.let { s -> runCatching { s.apply(VolumeShaper.Operation.REVERSE) }; spent = s }
            shaper = null
        }
        track.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
    }

    private companion object {
        const val FADE_MS = 120L
        val FADE: VolumeShaper.Configuration = VolumeShaper.Configuration.Builder()
            .setDuration(FADE_MS)
            .setCurve(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f))
            .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_CUBIC)
            .build()
    }
}
