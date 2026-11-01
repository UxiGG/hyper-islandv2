package com.custom.hyperisland.overlay

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.custom.hyperisland.data.*
import com.custom.hyperisland.service.AudioVisualizerManager
import com.custom.hyperisland.service.IslandCollectorService
import kotlin.math.roundToInt
import kotlin.math.sin

private const val METABALL_AGSL = """
    uniform shader content;
    uniform float threshold;

    half4 main(float2 coord) {
        half4 c = content.eval(coord);
        float smoothA = smoothstep(threshold - 0.07, threshold + 0.07, c.a);
        return half4(c.rgb * smoothA, smoothA);
    }
"""

class IslandOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private lateinit var touchRootLayout: IslandTouchLayout
    private lateinit var overlayLayoutParams: WindowManager.LayoutParams
    private lateinit var settingsRepository: SettingsRepository

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            IslandRuntimeBus.update { it.copy(batteryLevel = level, isCharging = charging) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        startForegroundChannel()
        settingsRepository = SettingsRepository.getInstance(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val staticWindowHeightPx = (360f * resources.displayMetrics.density).roundToInt()

        overlayLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            staticWindowHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        touchRootLayout = IslandTouchLayout(
            context = this,
            onDismissKeyboardFocus = {
                IslandRuntimeBus.update { it.copy(isReplying = false) }
            }
        ).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeViewModelStoreOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
        }

        val composeView = ComposeView(this).apply {
            setContent {
                val config by settingsRepository.config.collectAsState()
                val runtimeState by IslandRuntimeBus.runtimeState.collectAsState()

                LaunchedEffect(runtimeState.isReplying) {
                    setWindowFocusableForIme(runtimeState.isReplying)
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    DynamicIslandSurface(
                        config = config,
                        state = runtimeState,
                        isPreviewMode = false,
                        onBoundsChanged = { l, t, r, b ->
                            touchRootLayout.updateInteractiveBounds(l, t, r, b)
                        },
                        onLaunchFreeform = { pkg ->
                            launchPackageInHyperOsFreeform(pkg)
                        }
                    )
                }
            }
        }

        touchRootLayout.addView(
            composeView,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        )

        windowManager.addView(touchRootLayout, overlayLayoutParams)
    }

    private fun setWindowFocusableForIme(focusable: Boolean) {
        if (!::touchRootLayout.isInitialized || !::overlayLayoutParams.isInitialized) return

        touchRootLayout.isFocusableInputActive = focusable

        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        overlayLayoutParams.flags = if (focusable) {
            baseFlags
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        overlayLayoutParams.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }

        try {
            windowManager.updateViewLayout(touchRootLayout, overlayLayoutParams)
        } catch (_: Exception) {}

        if (!focusable) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(touchRootLayout.windowToken, 0)
        }
    }

    private fun startForegroundChannel() {
        val channelId = "hyper_island_overlay_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "HyperIsland 120Hz Engine",
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("HyperIsland активен")
            .setContentText("Аппаратный оверлей 120 Гц синхронизирован с вырезом камеры")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun launchPackageInHyperOsFreeform(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        val options = ActivityOptions.makeBasic()
        try {
            val method = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            method.invoke(options, 5)
        } catch (_: Exception) {}

        val bundle = options.toBundle() ?: Bundle()
        bundle.putBoolean("android.activity.launchWindowingMode", true)
        bundle.putInt("miui.WindowingMode", 5)

        try {
            startActivity(intent, bundle)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        if (::touchRootLayout.isInitialized) {
            windowManager.removeView(touchRootLayout)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
fun DynamicIslandSurface(
    config: IslandConfig,
    state: IslandRuntimeState,
    isPreviewMode: Boolean,
    onBoundsChanged: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onLaunchFreeform: (String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val isExpanded = state.mode == IslandDisplayMode.ExpandedCard

    val activeStiffness = if (isExpanded) config.expandStiffness else config.collapseStiffness
    val activeDamping = if (isExpanded) config.expandDamping else config.collapseDamping

    val dpSpring = remember(activeStiffness, activeDamping) {
        spring<Dp>(dampingRatio = activeDamping, stiffness = activeStiffness)
    }

    val cutoutDiameterDp = with(density) { (config.cutoutRadiusPx * 2f).toDp() }

    val targetWidth = when (state.mode) {
        IslandDisplayMode.IdleCutout -> cutoutDiameterDp + 6.dp
        is IslandDisplayMode.CompactPill -> {
            if (state.mode.showDetachedDroplet) (config.collapsedWidthDp + 44f).dp
            else config.collapsedWidthDp.dp
        }
        IslandDisplayMode.ExpandedCard -> config.expandedWidthDp.dp
        IslandDisplayMode.AuraWrap -> (config.collapsedWidthDp + 24f).dp
    }

    val targetHeight = when (state.mode) {
        IslandDisplayMode.IdleCutout -> cutoutDiameterDp + 6.dp
        is IslandDisplayMode.CompactPill -> config.collapsedHeightDp.dp
        IslandDisplayMode.ExpandedCard -> {
            val replyExtra = if (state.isReplying) 54f else 0f
            (config.expandedHeightDp + replyExtra).dp
        }
        IslandDisplayMode.AuraWrap -> (config.collapsedHeightDp + 10f).dp
    }

    val baseTopOffsetDp = with(density) { config.cutoutOffsetYPx.toDp() }
    val targetTopOffsetDp = when {
        state.isHyperOsNativeActive && config.coexistenceMode == CoexistenceMode.STACK_BELOW ->
            baseTopOffsetDp + config.nativeStackOffsetYDp.dp
        state.mode == IslandDisplayMode.ExpandedCard ->
            baseTopOffsetDp + config.expandedTopOffsetDp.dp
        else -> baseTopOffsetDp
    }

    val animatedWidth by animateDpAsState(targetValue = targetWidth, animationSpec = dpSpring, label = "width")
    val animatedHeight by animateDpAsState(targetValue = targetHeight, animationSpec = dpSpring, label = "height")
    val animatedTopOffset by animateDpAsState(targetValue = targetTopOffsetDp, animationSpec = dpSpring, label = "topY")

    val (targetTopRad, targetBotRad) = when {
        state.mode != IslandDisplayMode.ExpandedCard -> 50.dp to 50.dp
        config.shapePreset == ShapePreset.NEOMORPHIC_CAPSULE -> 48.dp to 48.dp
        config.shapePreset == ShapePreset.SQUIRCLE -> 32.dp to 36.dp
        config.shapePreset == ShapePreset.SUPERELLIPSE -> 22.dp to 44.dp
        else -> config.expandedTopRadiusDp.dp to config.expandedBottomRadiusDp.dp
    }

    val animTopRad by animateDpAsState(targetValue = targetTopRad, animationSpec = dpSpring, label = "topRad")
    val animBotRad by animateDpAsState(targetValue = targetBotRad, animationSpec = dpSpring, label = "botRad")

    val dropletGapDp by animateDpAsState(
        targetValue = if (state.mode is IslandDisplayMode.CompactPill && state.mode.showDetachedDroplet) 12.dp else 0.dp,
        animationSpec = dpSpring,
        label = "dropletGap"
    )

    val accentColor = remember(config.cardThemeMode, config.staticAccentArgb, state.media, state.notification) {
        when (config.cardThemeMode) {
            CardThemeMode.STATIC_ACCENT -> Color(config.staticAccentArgb)
            else -> state.notification?.accentColor ?: state.media?.dominantColor ?: Color(config.staticAccentArgb)
        }
    }

    val darkSurfaceColor = state.media?.darkSurfaceColor ?: Color(0xFF0E1017)
    val metaballShader = remember { RuntimeShader(METABALL_AGSL) }

    val handleGesture: (IslandGestureAction) -> Unit = { action ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        when (action) {
            IslandGestureAction.EXPAND_COLLAPSE -> {
                IslandRuntimeBus.update { curr ->
                    val next = if (curr.mode == IslandDisplayMode.ExpandedCard) {
                        if (curr.media != null) {
                            IslandDisplayMode.CompactPill(curr.media.isPlaying && config.enableMetaballShader)
                        } else {
                            IslandDisplayMode.IdleCutout
                        }
                    } else {
                        IslandDisplayMode.ExpandedCard
                    }
                    curr.copy(mode = next, isReplying = false)
                }
            }
            IslandGestureAction.OPEN_FREEFORM_WINDOW -> {
                val pkg = state.notification?.packageName ?: state.media?.packageName
                if (pkg != null) onLaunchFreeform(pkg)
            }
            IslandGestureAction.DISMISS_CARD -> {
                state.notification?.key?.let { IslandCollectorService.dismissNotification(it) }
                IslandRuntimeBus.update { curr ->
                    curr.copy(
                        notification = null,
                        isReplying = false,
                        mode = if (curr.media != null) IslandDisplayMode.CompactPill(curr.media.isPlaying)
                        else IslandDisplayMode.IdleCutout
                    )
                }
            }
            else -> IslandCollectorService.dispatchMediaCommand(context, action)
        }
    }

    Box(
        modifier = Modifier
            .padding(top = if (isPreviewMode) 8.dp else animatedTopOffset)
            .width(animatedWidth)
            .height(animatedHeight)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                onBoundsChanged(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            .graphicsLayer {
                if (!isPreviewMode) {
                    translationX = config.cutoutOffsetXPx
                }
                if (config.enableMetaballShader && state.mode is IslandDisplayMode.CompactPill) {
                    metaballShader.setFloatUniform("threshold", config.metaballThreshold)
                    val blurPx = 14f
                    val blurEffect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.DECAL)
                    val agslEffect = RenderEffect.createRuntimeShaderEffect(metaballShader, "content")
                    renderEffect = RenderEffect.createChainEffect(agslEffect, blurEffect).asComposeRenderEffect()
                }
            }
            .pointerInput(config, state.mode, state.media, state.notification) {
                detectTapGestures(
                    onTap = { handleGesture(config.onSingleTap) },
                    onDoubleTap = { handleGesture(config.onDoubleTap) },
                    onLongPress = { handleGesture(config.onLongPress) }
                )
            }
            .pointerInput(config) {
                var accumulatedDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accumulatedDx = 0f },
                    onHorizontalDrag = { _, dragAmount -> accumulatedDx += dragAmount },
                    onDragEnd = {
                        if (accumulatedDx < -60f) handleGesture(config.onSwipeLeft)
                        else if (accumulatedDx > 60f) handleGesture(config.onSwipeRight)
                    }
                )
            }
            .pointerInput(config, state.media, state.notification) {
                var accumulatedDy = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulatedDy = 0f },
                    onVerticalDrag = { _, dragAmount -> accumulatedDy += dragAmount },
                    onDragEnd = {
                        if (accumulatedDy > 55f) handleGesture(config.onSwipeDown)
                        else if (accumulatedDy < -45f && state.mode == IslandDisplayMode.ExpandedCard) {
                            handleGesture(IslandGestureAction.EXPAND_COLLAPSE)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when (state.mode) {
            IslandDisplayMode.IdleCutout -> {
                CutoutRingRenderer(
                    style = config.cutoutRingStyle,
                    batteryLevel = state.batteryLevel,
                    isPlaying = state.media?.isPlaying == true,
                    hasNotification = state.notification != null,
                    accentColor = accentColor
                )
            }

            IslandDisplayMode.AuraWrap -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(50))
                        .border(2.5.dp, accentColor, RoundedCornerShape(50))
                )
            }

            is IslandDisplayMode.CompactPill -> {
                CompactPillContent(
                    state = state,
                    config = config,
                    accentColor = accentColor,
                    dropletGapDp = dropletGapDp
                )
            }

            IslandDisplayMode.ExpandedCard -> {
                val cardShape = RoundedCornerShape(
                    topStart = animTopRad,
                    topEnd = animTopRad,
                    bottomStart = animBotRad,
                    bottomEnd = animBotRad
                )
                ExpandedCardContent(
                    state = state,
                    config = config,
                    cardShape = cardShape,
                    accentColor = accentColor,
                    darkSurfaceColor = darkSurfaceColor,
                    onActionGesture = handleGesture,
                    onLaunchFreeform = onLaunchFreeform
                )
            }
        }
    }
}

@Composable
private fun CutoutRingRenderer(
    style: CutoutRingStyle,
    batteryLevel: Int,
    isPlaying: Boolean,
    hasNotification: Boolean,
    accentColor: Color
) {
    if (style == CutoutRingStyle.NONE) return

    val infiniteTransition = rememberInfiniteTransition(label = "ringPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val scaleMod = if ((style == CutoutRingStyle.AUDIO_PULSE && isPlaying) ||
        (style == CutoutRingStyle.NOTIFICATION_GLOW && hasNotification)
    ) Modifier.scale(pulseScale) else Modifier

    Canvas(modifier = Modifier.fillMaxSize().then(scaleMod)) {
        val sweep = when (style) {
            CutoutRingStyle.BATTERY_ARC -> (batteryLevel.coerceIn(0, 100) / 100f) * 360f
            else -> 360f
        }
        drawArc(
            color = accentColor,
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun CompactPillContent(
    state: IslandRuntimeState,
    config: IslandConfig,
    accentColor: Color,
    dropletGapDp: Dp
) {
    val pillMode = state.mode as? IslandDisplayMode.CompactPill ?: return

    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(Color.Black)
                .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            state.media?.albumArt?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                )
            } ?: Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )

            Spacer(modifier = Modifier.width(32.dp))

            VisualizerBlock(
                style = if (config.visualizerStyle == VisualizerStyle.NONE)
                    VisualizerStyle.JUMPING_BARS_4 else config.visualizerStyle,
                isPlaying = state.media?.isPlaying == true,
                accentColor = accentColor,
                modifier = Modifier.size(width = 22.dp, height = 16.dp)
            )
        }

        if (pillMode.showDetachedDroplet) {
            Spacer(modifier = Modifier.width(dropletGapDp))
            Box(
                modifier = Modifier
                    .size(config.collapsedHeightDp.dp)
                    .clip(CircleShape)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        }
    }
}

@Composable
private fun ExpandedCardContent(
    state: IslandRuntimeState,
    config: IslandConfig,
    cardShape: RoundedCornerShape,
    accentColor: Color,
    darkSurfaceColor: Color,
    onActionGesture: (IslandGestureAction) -> Unit,
    onLaunchFreeform: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val maxVol = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var currentVol by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat())
    }

    val backgroundBrush = remember(config.cardThemeMode, accentColor, darkSurfaceColor, config.backgroundAlpha) {
        val alpha = config.backgroundAlpha.coerceIn(0.6f, 1f)
        when (config.cardThemeMode) {
            CardThemeMode.ADAPTIVE_GRADIENT -> Brush.verticalGradient(
                colors = listOf(
                    accentColor.copy(alpha = alpha * 0.42f),
                    darkSurfaceColor.copy(alpha = alpha),
                    Color(0xFF07080C).copy(alpha = alpha)
                )
            )
            CardThemeMode.ONYX_NEON_STROKE,
            CardThemeMode.STATIC_ACCENT -> Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF0B0C10).copy(alpha = alpha),
                    Color(0xFF050608).copy(alpha = alpha)
                )
            )
        }
    }

    val borderModifier = if (config.cardThemeMode == CardThemeMode.ONYX_NEON_STROKE) {
        Modifier.border(1.5.dp, accentColor, cardShape)
    } else {
        Modifier.border(0.8.dp, Color.White.copy(alpha = 0.14f), cardShape)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(cardShape)
            .then(borderModifier)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    if (config.glassBlurRadiusDp > 0.5f) {
                        val px = config.glassBlurRadiusDp * density
                        renderEffect = RenderEffect.createBlurEffect(px, px, Shader.TileMode.MIRROR)
                            .asComposeRenderEffect()
                    }
                }
                .background(backgroundBrush)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(config.expandedPaddingDp.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (config.showAlbumArt) {
                    AlbumArtModule(
                        bitmap = state.media?.albumArt,
                        shapeType = config.artShape,
                        sizeDp = config.artSizeDp,
                        beatPulse = config.artBeatPulse && (state.media?.isPlaying == true),
                        accentColor = accentColor
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    val primaryTitle = state.notification?.title
                        ?: state.media?.title
                        ?: "HyperIsland Studio"
                    val secondarySubtitle = state.notification?.message
                        ?: state.media?.artist
                        ?: "Poco M8 5G • 120Hz Active"

                    Text(
                        text = primaryTitle,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = secondarySubtitle,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (config.visualizerStyle != VisualizerStyle.NONE) {
                    Spacer(modifier = Modifier.width(8.dp))
                    VisualizerBlock(
                        style = config.visualizerStyle,
                        isPlaying = state.media?.isPlaying == true,
                        accentColor = accentColor,
                        modifier = Modifier.size(width = 34.dp, height = 28.dp)
                    )
                }
            }

            if (state.media != null) {
                ScrubberModule(
                    media = state.media,
                    style = config.scrubberStyle,
                    accentColor = accentColor,
                    onSeekRatio = { ratio ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val targetMs = (ratio * state.media.durationMs).toLong()
                        IslandCollectorService.seekTo(context, targetMs)
                    }
                )
            }

            if (config.showVolumeSlider) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = currentVol,
                        onValueChange = { newVal ->
                            currentVol = newVal
                            val targetIdx = (newVal * maxVol).roundToInt()
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIdx, 0)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        modifier = Modifier.height(22.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            val replySlice = state.notification?.replyAction

            AnimatedVisibility(
                visible = state.isReplying && state.notification != null && replySlice != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                if (state.notification != null && replySlice != null) {
                    DirectReplyInputBar(
                        notificationKey = state.notification.key,
                        replySlice = replySlice,
                        accentColor = accentColor,
                        onCancelReply = {
                            IslandRuntimeBus.update { it.copy(isReplying = false) }
                        }
                    )
                }
            }

            if (config.showActionDock && !state.isReplying) {
                ActionDockModule(
                    state = state,
                    accentColor = accentColor,
                    onMediaAction = onActionGesture,
                    onStartDirectReply = {
                        IslandRuntimeBus.update { it.copy(isReplying = true) }
                    },
                    onOpenFreeform = {
                        val pkg = state.notification?.packageName ?: state.media?.packageName
                        if (pkg != null) onLaunchFreeform(pkg)
                    }
                )
            }
        }
    }
}

@Composable
private fun DirectReplyInputBar(
    notificationKey: String,
    replySlice: ReplyActionSlice,
    accentColor: Color,
    onCancelReply: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        delay(80L)
        try {
            focusRequester.requestFocus()
            keyboardController?.show()
        } catch (_: Exception) {}
    }

    val submitReply = {
        if (text.isNotBlank()) {
            keyboardController?.hide()
            IslandCollectorService.sendDirectReply(
                context = context,
                notificationKey = notificationKey,
                replySlice = replySlice,
                replyText = text
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, accentColor.copy(alpha = 0.65f), RoundedCornerShape(50))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "${replySlice.label}...",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 13.sp
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(accentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitReply() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }

        IconButton(
            onClick = {
                keyboardController?.hide()
                onCancelReply()
            },
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Отмена",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }

        IconButton(
            onClick = submitReply,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (text.isNotBlank()) accentColor else accentColor.copy(alpha = 0.25f))
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Отправить",
                tint = if (text.isNotBlank()) Color.Black else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun AlbumArtModule(
    bitmap: Bitmap?,
    shapeType: ArtShape,
    sizeDp: Float,
    beatPulse: Boolean,
    accentColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "artBeat")
    val beatScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (beatPulse) 1.08f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "beatScale"
    )

    val clipShape = when (shapeType) {
        ArtShape.CIRCLE -> CircleShape
        ArtShape.ROUNDED_SQUARE -> RoundedCornerShape(10.dp)
        ArtShape.SQUIRCLE -> RoundedCornerShape(32)
    }

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .scale(beatScale)
            .clip(clipShape)
            .background(accentColor.copy(alpha = 0.25f))
            .border(1.dp, accentColor.copy(alpha = 0.6f), clipShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size((sizeDp * 0.5f).dp)
            )
        }
    }
}

@Composable
private fun ScrubberModule(
    media: MediaSlice,
    style: ScrubberStyle,
    accentColor: Color,
    onSeekRatio: (Float) -> Unit
) {
    var dragRatio by remember(media.progressRatio) { mutableFloatStateOf(media.progressRatio) }
    var isDragging by remember { mutableStateOf(false) }

    val displayRatio = if (isDragging) dragRatio else media.progressRatio

    when (style) {
        ScrubberStyle.SLIM_BAR -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayRatio)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        }
        ScrubberStyle.INTERACTIVE_THICK -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                dragRatio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragRatio = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                isDragging = false
                                onSeekRatio(dragRatio)
                            },
                            onDragCancel = { isDragging = false }
                        )
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayRatio)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(accentColor.copy(alpha = 0.7f), accentColor)
                            )
                        )
                )
            }
        }
    }
}

@Composable
fun VisualizerBlock(
    style: VisualizerStyle,
    isPlaying: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val visualizerManager = remember { AudioVisualizerManager.getInstance(context) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) visualizerManager.startCapturing(true)
        else visualizerManager.stopCapturing()
    }

    val fftBands by visualizerManager.fftBands.collectAsState()
    val waveform by visualizerManager.waveformData.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "fallbackViz")
    val fallbackPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fallbackPhase"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        when (style) {
            VisualizerStyle.NONE -> {}
            VisualizerStyle.JUMPING_BARS_4 -> {
                val barCount = 4
                val spacing = w / (barCount * 2f)
                for (i in 0 until barCount) {
                    val factor = if (isPlaying && i < fftBands.size) {
                        (fftBands[i] * 1.8f).coerceIn(0.15f, 1f)
                    } else if (isPlaying) {
                        (sin(fallbackPhase + i * 1.2f) * 0.4f + 0.5f).coerceIn(0.2f, 1f)
                    } else 0.15f

                    val barH = h * factor
                    val x = i * (w / barCount) + spacing / 2f
                    drawLine(
                        color = accentColor,
                        start = Offset(x, (h - barH) / 2f),
                        end = Offset(x, (h + barH) / 2f),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            VisualizerStyle.SINE_WAVE -> {
                val path = Path()
                val steps = waveform.size.coerceAtLeast(12)
                for (s in 0 until steps) {
                    val x = (s.toFloat() / (steps - 1)) * w
                    val normVal = if (isPlaying && s < waveform.size) waveform[s] else 0.5f
                    val y = h * (0.15f + 0.7f * (1f - normVal))
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = accentColor,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            VisualizerStyle.CIRCULAR_RING -> {
                val avgFft = if (isPlaying && fftBands.isNotEmpty()) fftBands.average().toFloat() else 0.3f
                val radius = (minOf(w, h) / 2f) * (0.65f + avgFft * 0.45f)
                drawCircle(
                    color = accentColor,
                    radius = radius,
                    style = Stroke(width = 2.2.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun ActionDockModule(
    state: IslandRuntimeState,
    accentColor: Color,
    onMediaAction: (IslandGestureAction) -> Unit,
    onStartDirectReply: () -> Unit,
    onOpenFreeform: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.notification != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.notification.replyAction?.let { reply ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(accentColor)
                            .clickable { onStartDirectReply() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = reply.label,
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                state.notification.actions.take(2).forEach { (label, pendingIntent) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(accentColor.copy(alpha = 0.22f))
                            .clickable {
                                try {
                                    pendingIntent.send()
                                } catch (_: Exception) {}
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = accentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onMediaAction(IslandGestureAction.MEDIA_PREV) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White)
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = { onMediaAction(IslandGestureAction.MEDIA_PLAY_PAUSE) },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                ) {
                    Icon(
                        imageVector = if (state.media?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = { onMediaAction(IslandGestureAction.MEDIA_NEXT) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onOpenFreeform,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "Freeform",
                    tint = accentColor,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = { onMediaAction(IslandGestureAction.DISMISS_CARD) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}
