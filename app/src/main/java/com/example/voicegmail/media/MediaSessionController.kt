package com.example.voicegmail.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.example.voicegmail.MainActivity
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a MediaSession and media-style notification for TTS playback control.
 *
 * Allows the user to control TTS reading via:
 * - BT headset media buttons (play/pause, next track, previous track)
 * - Wired headset buttons
 * - Notification action buttons
 * - Lock screen media controls
 *
 * Maps media actions to voice commands:
 * - Play/Pause → Pause or Continue reading
 * - Next → Next email / next article
 * - Previous → Previous email / previous article
 */
@Singleton
class MediaSessionController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "MediaSession"

    private var mediaSession: MediaSessionCompat? = null
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /** Callbacks set by InboxViewModel to handle media button events. */
    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null

    private var isPlaying = false
    private var currentTitle = "VoiceGmail"
    private var currentSubtitle = ""

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun initialize() {
        if (mediaSession != null) return
        createNotificationChannel()

        mediaSession = MediaSessionCompat(context, "VoiceGmailMedia").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    DebugLogger.log(tag, "Media button: PLAY")
                    this@MediaSessionController.onPlay?.invoke()
                }

                override fun onPause() {
                    DebugLogger.log(tag, "Media button: PAUSE")
                    this@MediaSessionController.onPause?.invoke()
                }

                override fun onSkipToNext() {
                    DebugLogger.log(tag, "Media button: NEXT")
                    this@MediaSessionController.onNext?.invoke()
                }

                override fun onSkipToPrevious() {
                    DebugLogger.log(tag, "Media button: PREVIOUS")
                    this@MediaSessionController.onPrevious?.invoke()
                }

                override fun onStop() {
                    DebugLogger.log(tag, "Media button: STOP")
                    this@MediaSessionController.onPause?.invoke()
                }
            })

            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }

        updatePlaybackState(false)
        DebugLogger.log(tag, "MediaSession initialized")
    }

    fun release() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        notificationManager.cancel(NOTIFICATION_ID)
    }

    // ------------------------------------------------------------------
    // State updates
    // ------------------------------------------------------------------

    fun updateState(playing: Boolean, title: String = currentTitle, subtitle: String = currentSubtitle) {
        isPlaying = playing
        currentTitle = title
        currentSubtitle = subtitle
        updatePlaybackState(playing)
        updateMetadata(title, subtitle)
        showNotification()
    }

    fun setPlaying(title: String, subtitle: String = "") {
        updateState(playing = true, title = title, subtitle = subtitle)
    }

    fun setPaused() {
        updateState(playing = false)
    }

    fun setStopped() {
        isPlaying = false
        currentTitle = "VoiceGmail"
        currentSubtitle = ""
        updatePlaybackState(false)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun updatePlaybackState(playing: Boolean) {
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .setActions(actions)
            .build()

        mediaSession?.setPlaybackState(playbackState)
    }

    private fun updateMetadata(title: String, subtitle: String) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "VoiceGmail")
            .build()

        mediaSession?.setMetadata(metadata)
    }

    private fun showNotification() {
        val session = mediaSession ?: return

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause, "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PAUSE)
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play, "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY)
            ).build()
        }

        val prevAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous, "Previous",
            MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        ).build()

        val nextAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next, "Next",
            MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        ).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentSubtitle)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VoiceGmail Reading",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media controls for TTS reading"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "voicegmail_media"
        private const val NOTIFICATION_ID = 1002
    }
}
