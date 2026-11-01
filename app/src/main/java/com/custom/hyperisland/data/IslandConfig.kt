package com.custom.hyperisland.data

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color

enum class CoexistenceMode(val title: String) {
    STACK_BELOW("Второй ярус под капсулой HyperOS"),
    AURA_RING("Неоновый контур вокруг HyperOS"),
    YIELD_TO_NATIVE("Уступать место нативному острову"),
    OVERRIDE_ALL("Полное перекрытие поверх системы")
}

enum class CutoutRingStyle(val title: String) {
    NONE("Выключено"),
    BATTERY_ARC("Дуга уровня заряда"),
    AUDIO_PULSE("Пульсация в такт басам"),
    NOTIFICATION_GLOW("Индикатор уведомлений")
}

enum class ShapePreset(val title: String) {
    CUSTOM_CORNERS("Независимые радиусы (Top / Bottom)"),
    SQUIRCLE("Адаптивный сквиркл"),
    NEOMORPHIC_CAPSULE("Неоморфная капсула"),
    SUPERELLIPSE("Сглаженный суперэллипс")
}

enum class ArtShape(val title: String) {
    ROUNDED_SQUARE("Скругленный квадрат"),
    CIRCLE("Виниловый круг"),
    SQUIRCLE("Сквиркл")
}

enum class ScrubberStyle(val title: String) {
    SLIM_BAR("Тонкая линия прогресса"),
    INTERACTIVE_THICK("Интерактивный скраббер (Seek)")
}

enum class VisualizerStyle(val title: String) {
    NONE("Выключен"),
    JUMPING_BARS_4("4-полосный спектр"),
    SINE_WAVE("Синусоидальная волна"),
    CIRCULAR_RING("Круговой радар")
}

enum class CardThemeMode(val title: String) {
    ADAPTIVE_GRADIENT("Градиент из обложки Palette API"),
    ONYX_NEON_STROKE("Глубокий оникс + неоновый контур 1.5dp"),
    STATIC_ACCENT("Статический кастомный акцент")
}

enum class IslandGestureAction(val title: String) {
    EXPAND_COLLAPSE("Раскрыть / Свернуть остров"),
    OPEN_FREEFORM_WINDOW("Плавающее окно HyperOS Freeform"),
    MEDIA_PLAY_PAUSE("Воспроизведение / Пауза"),
    MEDIA_NEXT("Следующий трек"),
    MEDIA_PREV("Предыдущий трек"),
    DISMISS_CARD("Скрыть текущую карточку")
}

data class ReplyActionSlice(
    val label: String,
    val remoteInput: RemoteInput,
    val actionIntent: PendingIntent
)

data class IslandConfig(
    val cutoutOffsetXPx: Float = 0f,
    val cutoutOffsetYPx: Float = 10f,
    val cutoutRadiusPx: Float = 36f,
    val collapsedWidthDp: Float = 128f,
    val collapsedHeightDp: Float = 36f,
    val cutoutRingStyle: CutoutRingStyle = CutoutRingStyle.BATTERY_ARC,

    val expandedWidthDp: Float = 368f,
    val expandedHeightDp: Float = 188f,
    val expandedTopRadiusDp: Float = 28f,
    val expandedBottomRadiusDp: Float = 38f,
    val shapePreset: ShapePreset = ShapePreset.CUSTOM_CORNERS,
    val expandedPaddingDp: Float = 16f,
    val expandedTopOffsetDp: Float = 8f,

    val showAlbumArt: Boolean = true,
    val artShape: ArtShape = ArtShape.SQUIRCLE,
    val artSizeDp: Float = 56f,
    val artBeatPulse: Boolean = true,
    val scrubberStyle: ScrubberStyle = ScrubberStyle.INTERACTIVE_THICK,
    val showVolumeSlider: Boolean = false,
    val visualizerStyle: VisualizerStyle = VisualizerStyle.JUMPING_BARS_4,
    val showActionDock: Boolean = true,

    val cardThemeMode: CardThemeMode = CardThemeMode.ADAPTIVE_GRADIENT,
    val backgroundAlpha: Float = 0.92f,
    val glassBlurRadiusDp: Float = 18f,
    val staticAccentArgb: Int = 0xFF00E5FF.toInt(),

    val expandStiffness: Float = 340f,
    val expandDamping: Float = 0.58f,
    val collapseStiffness: Float = 680f,
    val collapseDamping: Float = 0.88f,
    val enableMetaballShader: Boolean = true,
    val metaballThreshold: Float = 0.45f,

    val whitelistedPackages: Set<String> = emptySet(),

    val coexistenceMode: CoexistenceMode = CoexistenceMode.STACK_BELOW,
    val nativeStackOffsetYDp: Float = 46f,
    val suppressNativeFocus: Boolean = false,
    val onSingleTap: IslandGestureAction = IslandGestureAction.EXPAND_COLLAPSE,
    val onDoubleTap: IslandGestureAction = IslandGestureAction.MEDIA_PLAY_PAUSE,
    val onLongPress: IslandGestureAction = IslandGestureAction.OPEN_FREEFORM_WINDOW,
    val onSwipeLeft: IslandGestureAction = IslandGestureAction.MEDIA_NEXT,
    val onSwipeRight: IslandGestureAction = IslandGestureAction.MEDIA_PREV,
    val onSwipeDown: IslandGestureAction = IslandGestureAction.OPEN_FREEFORM_WINDOW
)

sealed interface IslandDisplayMode {
    data object IdleCutout : IslandDisplayMode
    data class CompactPill(val showDetachedDroplet: Boolean = false) : IslandDisplayMode
    data object ExpandedCard : IslandDisplayMode
    data object AuraWrap : IslandDisplayMode
}

data class MediaSlice(
    val packageName: String,
    val title: String,
    val artist: String,
    val albumArt: Bitmap?,
    val isPlaying: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val dominantColor: Color = Color(0xFF00E5FF),
    val darkSurfaceColor: Color = Color(0xFF10121A)
) {
    val progressRatio: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

data class NotificationSlice(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val message: String,
    val contentIntent: PendingIntent?,
    val actions: List<Pair<String, PendingIntent>>,
    val replyAction: ReplyActionSlice? = null,
    val accentColor: Color = Color(0xFF00E5FF)
)

data class IslandRuntimeState(
    val mode: IslandDisplayMode = IslandDisplayMode.IdleCutout,
    val isHyperOsNativeActive: Boolean = false,
    val isReplying: Boolean = false,
    val media: MediaSlice? = null,
    val notification: NotificationSlice? = null,
    val batteryLevel: Int = 88,
    val isCharging: Boolean = false
)
