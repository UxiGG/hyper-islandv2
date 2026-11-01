package com.custom.hyperisland.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import com.custom.hyperisland.data.*
import kotlinx.coroutines.*

class IslandCollectorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var settingsRepository: SettingsRepository

    private var activeController: MediaController? = null
    private var progressTickerJob: Job? = null
    private var autoCollapseJob: Job? = null

    private val hyperOsFocusSystemPackages = setOf(
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.android.deskclock",
        "com.android.soundrecorder",
        "com.miui.screenrecorder"
    )

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        attachToPrimaryMediaController(controllers)
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshMediaSlice()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshMediaSlice()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        settingsRepository = SettingsRepository.getInstance(this)
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        val cn = ComponentName(this, IslandCollectorService::class.java)
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, cn)
            attachToPrimaryMediaController(mediaSessionManager.getActiveSessions(cn))
        } catch (_: SecurityException) {}
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        progressTickerJob?.cancel()
        autoCollapseJob?.cancel()
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
    }

    private fun attachToPrimaryMediaController(controllers: List<MediaController>?) {
        activeController?.unregisterCallback(mediaControllerCallback)
        activeController = controllers?.firstOrNull {
            val state = it.playbackState?.state
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        } ?: controllers?.firstOrNull()

        activeController?.registerCallback(mediaControllerCallback)
        refreshMediaSlice()
    }

    private fun refreshMediaSlice() {
        val controller = activeController
        if (controller == null) {
            progressTickerJob?.cancel()
            IslandRuntimeBus.update { it.copy(media = null) }
            recalculateMode()
            return
        }

        val metadata = controller.metadata
        val playback = controller.playbackState
        val isPlaying = playback?.state == PlaybackState.STATE_PLAYING

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Мультимедиа"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: controller.packageName
        val artBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 1L).coerceAtLeast(1L)
        val position = playback?.position ?: 0L

        scope.launch(Dispatchers.Default) {
            val (dominant, darkSurface) = extractColorsWithPalette(artBitmap)
            withContext(Dispatchers.Main) {
                val slice = MediaSlice(
                    packageName = controller.packageName,
                    title = title,
                    artist = artist,
                    albumArt = artBitmap,
                    isPlaying = isPlaying,
                    durationMs = duration,
                    positionMs = position,
                    dominantColor = dominant,
                    darkSurfaceColor = darkSurface
                )
                IslandRuntimeBus.update { it.copy(media = slice) }
                recalculateMode()
                manageProgressTicker(isPlaying)
            }
        }
    }

    private fun manageProgressTicker(isPlaying: Boolean) {
        progressTickerJob?.cancel()
        if (!isPlaying) return
        progressTickerJob = scope.launch {
            while (isActive) {
                delay(250L)
                val ctrl = activeController ?: break
                val pos = ctrl.playbackState?.position ?: continue
                IslandRuntimeBus.update { current ->
                    val m = current.media ?: return@update current
                    current.copy(media = m.copy(positionMs = pos))
                }
            }
        }
    }

    private fun extractColorsWithPalette(bitmap: Bitmap?): Pair<Color, Color> {
        if (bitmap == null || bitmap.isRecycled) {
            return Color(0xFF00E5FF) to Color(0xFF10121A)
        }
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val vibrantInt = palette.getVibrantColor(
            palette.getLightVibrantColor(
                palette.getDominantColor(0xFF00E5FF.toInt())
            )
        )
        val darkMutedInt = palette.getDarkMutedColor(0xFF10121A.toInt())
        return Color(vibrantInt) to Color(darkMutedInt)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val config = settingsRepository.config.value

        if (isNativeHyperOsFocusNotification(sbn, extras)) {
            IslandRuntimeBus.update { it.copy(isHyperOsNativeActive = true) }
            recalculateMode()
            return
        }

        if (sbn.isOngoing && extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        if (config.whitelistedPackages.isNotEmpty() && sbn.packageName !in config.whitelistedPackages) {
            return
        }

        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        var extractedReplySlice: ReplyActionSlice? = null
        val regularActions = mutableListOf<Pair<String, android.app.PendingIntent>>()

        sbn.notification.actions?.forEach { action ->
            val actionTitle = action.title?.toString() ?: return@forEach
            val actionIntent = action.actionIntent ?: return@forEach
            val remoteInputs = action.remoteInputs

            if (!remoteInputs.isNullOrEmpty() && extractedReplySlice == null) {
                val primaryRemoteInput = remoteInputs.firstOrNull { it.allowFreeFormInput } ?: remoteInputs.first()
                extractedReplySlice = ReplyActionSlice(
                    label = actionTitle,
                    remoteInput = primaryRemoteInput,
                    actionIntent = actionIntent
                )
            } else {
                regularActions.add(actionTitle to actionIntent)
            }
        }

        val appName = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val notifColor = if (sbn.notification.color != 0) {
            Color(sbn.notification.color or 0xFF000000.toInt())
        } else {
            Color(config.staticAccentArgb)
        }

        val notifSlice = NotificationSlice(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            message = message,
            contentIntent = sbn.notification.contentIntent,
            actions = regularActions,
            replyAction = extractedReplySlice,
            accentColor = notifColor
        )

        IslandRuntimeBus.update {
            it.copy(
                notification = notifSlice,
                isReplying = false,
                mode = IslandDisplayMode.ExpandedCard
            )
        }

        scheduleAutoCollapse(sbn.key)
    }

    private fun scheduleAutoCollapse(notificationKey: String) {
        autoCollapseJob?.cancel()
        autoCollapseJob = scope.launch {
            delay(5000L)
            while (IslandRuntimeBus.runtimeState.value.isReplying) {
                delay(1000L)
            }
            IslandRuntimeBus.update { curr ->
                if (curr.notification?.key == notificationKey) {
                    curr.copy(notification = null, isReplying = false)
                } else curr
            }
            recalculateMode()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isNativeHyperOsFocusNotification(sbn, sbn.notification.extras)) {
            IslandRuntimeBus.update { it.copy(isHyperOsNativeActive = false) }
        }
        IslandRuntimeBus.update { curr ->
            if (curr.notification?.key == notificationKey) {
                curr.copy(notification = null)
            } else curr
        }
        recalculateMode()
    }

    private fun isNativeHyperOsFocusNotification(sbn: StatusBarNotification, extras: Bundle): Boolean {
        val hasFocusMetadata = extras.containsKey("miui.focus.param") ||
                extras.containsKey("miui.focus.pics") ||
                extras.containsKey("miui.focus.ticker") ||
                extras.containsKey("miui.focus.rv")
        val isSystemOngoing = sbn.isOngoing && sbn.packageName in hyperOsFocusSystemPackages
        return hasFocusMetadata || isSystemOngoing
    }

    private fun recalculateMode() {
        val cfg = settingsRepository.config.value
        IslandRuntimeBus.update { curr ->
            val nextMode = when {
                curr.isHyperOsNativeActive && cfg.coexistenceMode == CoexistenceMode.YIELD_TO_NATIVE ->
                    IslandDisplayMode.IdleCutout

                curr.isHyperOsNativeActive && cfg.coexistenceMode == CoexistenceMode.AURA_RING ->
                    IslandDisplayMode.AuraWrap

                curr.notification != null ->
                    IslandDisplayMode.ExpandedCard

                curr.media != null ->
                    IslandDisplayMode.CompactPill(
                        showDetachedDroplet = cfg.enableMetaballShader && curr.media.isPlaying
                    )

                else -> IslandDisplayMode.IdleCutout
            }
            curr.copy(mode = nextMode)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        @Volatile
        private var instance: IslandCollectorService? = null

        fun dismissNotification(key: String) {
            try {
                instance?.cancelNotification(key)
            } catch (_: Exception) {}
        }

        fun seekTo(context: Context, positionMs: Long) {
            val controller = getPrimaryController(context) ?: return
            controller.transportControls.seekTo(positionMs)
        }

        fun sendDirectReply(
            context: Context,
            notificationKey: String,
            replySlice: ReplyActionSlice,
            replyText: String
        ): Boolean {
            if (replyText.isBlank()) return false
            return try {
                val resultsBundle = Bundle().apply {
                    putCharSequence(replySlice.remoteInput.resultKey, replyText.trim())
                }

                val fillInIntent = Intent().apply {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    RemoteInput.addResultsToIntent(arrayOf(replySlice.remoteInput), this, resultsBundle)
                }

                val options = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }

                replySlice.actionIntent.send(
                    context, 0, fillInIntent, null, null, null, options.toBundle()
                )

                dismissNotification(notificationKey)
                IslandRuntimeBus.update { curr ->
                    curr.copy(
                        isReplying = false,
                        notification = null,
                        mode = if (curr.media != null) IslandDisplayMode.CompactPill(curr.media.isPlaying)
                        else IslandDisplayMode.IdleCutout
                    )
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun dispatchMediaCommand(context: Context, action: IslandGestureAction) {
            val controller = getPrimaryController(context) ?: return
            when (action) {
                IslandGestureAction.MEDIA_PLAY_PAUSE -> {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                }
                IslandGestureAction.MEDIA_NEXT -> controller.transportControls.skipToNext()
                IslandGestureAction.MEDIA_PREV -> controller.transportControls.skipToPrevious()
                else -> {}
            }
        }

        private fun getPrimaryController(context: Context): MediaController? {
            val mm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(context, IslandCollectorService::class.java)
            return try {
                mm.getActiveSessions(cn).firstOrNull()
            } catch (_: SecurityException) {
                null
            }
        }
    }
}
