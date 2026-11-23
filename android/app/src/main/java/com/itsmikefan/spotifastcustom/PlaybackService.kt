package com.itsmikefan.spotifastcustom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps music playing with the screen off or the app closed: a foreground
 * service with a media notification, lock-screen and headset controls,
 * audio focus, and a wake lock while playing.
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSessionCompat
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var audio: AudioManager
    private var watching: Job? = null
    private var shown: PlayState? = null

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setOnAudioFocusChangeListener { change ->
            // A call or another player took over: pause rather than talk over it.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Playback.command(Core.PAUSE)
            }
        }
        .build()

    private val noisy = object : BroadcastReceiver() {
        // Headphones unplugged.
        override fun onReceive(context: Context, intent: Intent) = Playback.command(Core.PAUSE)
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spotifast:playback")
            .apply { setReferenceCounted(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSessionCompat(this, "Spotifast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = Playback.command(Core.PLAY)
                override fun onPause() = Playback.command(Core.PAUSE)
                override fun onSkipToNext() = Playback.command(Core.NEXT)
                override fun onSkipToPrevious() = Playback.command(Core.PREVIOUS)
                override fun onSeekTo(pos: Long) = Playback.command(Core.SEEK, pos.toInt())
            })
            setSessionActivity(
                PendingIntent.getActivity(
                    this@PlaybackService, 0, Intent(this@PlaybackService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION, notification(Playback.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        MediaButtonReceiver.handleIntent(session, intent)
        if (watching == null) {
            watching = Playback.scope.launch { Playback.state.collect(::show) }
            Playback.scope.launch { Settings.state.collect(::volumeKeys) }
        }
        return START_NOT_STICKY
    }

    /** With "Volume keys control the app" on, the phone's volume buttons move the app's slider instead. */
    private val keys = object : androidx.media.VolumeProviderCompat(androidx.media.VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE, 100, Settings.value.volume) {
        override fun onSetVolumeTo(volume: Int) = Playback.setVolume(volume)
        override fun onAdjustVolume(direction: Int) {
            if (direction != 0) Playback.setVolume(Settings.value.volume + 5 * direction)
        }
    }
    private var remoteKeys: Boolean? = null

    private fun volumeKeys(s: AppSettings) {
        if (s.volumeKeysApp != remoteKeys) {
            remoteKeys = s.volumeKeysApp
            if (s.volumeKeysApp) session.setPlaybackToRemote(keys) else session.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        }
        if (keys.currentVolume != s.volume) keys.currentVolume = s.volume
    }

    private fun show(state: PlayState) {
        val previous = shown
        shown = state
        if (state.playing != previous?.playing) {
            if (state.playing) {
                audio.requestAudioFocus(focus)
                wakeLock.acquire()
            } else if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
        if (state.uri != previous?.uri || state.name != previous?.name) {
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.name)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artists)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs.toLong())
                    .build()
            )
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (state.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    state.positionMs.toLong(),
                    if (state.playing) Playback.effects.value.let { if (it.on) it.ratePercent / 100f else 1f } else 0f,
                )
                .build()
        )
        if (state.playing != previous?.playing || state.uri != previous.uri || state.name != previous?.name) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
        }
    }

    private fun notification(state: PlayState) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(state.name.ifEmpty { "Spotifast Custom" })
        .setContentText(state.artists)
        .setContentIntent(session.controller.sessionActivity)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(state.playing)
        .addAction(android.R.drawable.ic_media_previous, "Previous", button(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
        .addAction(
            if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (state.playing) "Pause" else "Play",
            button(if (state.playing) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY),
        )
        .addAction(android.R.drawable.ic_media_next, "Next", button(PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
        .setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        .build()

    private fun button(action: Long) = MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)

    override fun onDestroy() {
        watching?.cancel()
        unregisterReceiver(noisy)
        if (wakeLock.isHeld) wakeLock.release()
        audio.abandonAudioFocusRequest(focus)
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "playback"
        const val NOTIFICATION = 1
    }
}
