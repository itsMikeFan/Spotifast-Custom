package com.itsmikefan.spotifastcustom

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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

    /** Spotify Connect and Cast receivers on the network as a JSON array, or {"error": ...}. Blocks for [timeoutMs]. */
    external fun receivers(timeoutMs: Int): String?

    /** Starts Spotify on a receiver row from [receivers] and signs it in; null on success, else why not. */
    external fun activate(receiver: String): String?

    /** The visualizer's bar heights, 0 to 1. */
    external fun bands(): FloatArray

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

    @Keep
    fun write(samples: FloatArray, count: Int) {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        track.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
    }
}
