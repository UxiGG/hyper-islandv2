package com.custom.hyperisland.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hyper_island_prefs")

object IslandRuntimeBus {
    private val _runtimeState = MutableStateFlow(IslandRuntimeState())
    val runtimeState: StateFlow<IslandRuntimeState> = _runtimeState.asStateFlow()

    fun update(reducer: (IslandRuntimeState) -> IslandRuntimeState) {
        _runtimeState.update(reducer)
    }
}

class SettingsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow(IslandConfig())
    val config: StateFlow<IslandConfig> = _config.asStateFlow()

    init {
        scope.launch {
            appContext.dataStore.data
                .catch { emit(emptyPreferences()) }
                .collect { prefs ->
                    _config.value = mapPreferencesToConfig(prefs)
                }
        }
    }

    fun updateConfig(transform: (IslandConfig) -> IslandConfig) {
        val updated = transform(_config.value)
        _config.value = updated
        scope.launch {
            saveConfigToPreferences(updated)
        }
    }

    private fun mapPreferencesToConfig(prefs: Preferences): IslandConfig {
        val def = IslandConfig()
        return IslandConfig(
            cutoutOffsetXPx = prefs[Keys.CUTOUT_OFFSET_X] ?: def.cutoutOffsetXPx,
            cutoutOffsetYPx = prefs[Keys.CUTOUT_OFFSET_Y] ?: def.cutoutOffsetYPx,
            cutoutRadiusPx = prefs[Keys.CUTOUT_RADIUS] ?: def.cutoutRadiusPx,
            collapsedWidthDp = prefs[Keys.COLLAPSED_W] ?: def.collapsedWidthDp,
            collapsedHeightDp = prefs[Keys.COLLAPSED_H] ?: def.collapsedHeightDp,
            cutoutRingStyle = enumValueOrDefault(prefs[Keys.RING_STYLE], def.cutoutRingStyle),

            expandedWidthDp = prefs[Keys.EXPANDED_W] ?: def.expandedWidthDp,
            expandedHeightDp = prefs[Keys.EXPANDED_H] ?: def.expandedHeightDp,
            expandedTopRadiusDp = prefs[Keys.EXP_TOP_RAD] ?: def.expandedTopRadiusDp,
            expandedBottomRadiusDp = prefs[Keys.EXP_BOT_RAD] ?: def.expandedBottomRadiusDp,
            shapePreset = enumValueOrDefault(prefs[Keys.SHAPE_PRESET], def.shapePreset),
            expandedPaddingDp = prefs[Keys.EXP_PADDING] ?: def.expandedPaddingDp,
            expandedTopOffsetDp = prefs[Keys.EXP_TOP_OFFSET] ?: def.expandedTopOffsetDp,

            showAlbumArt = prefs[Keys.SHOW_ART] ?: def.showAlbumArt,
            artShape = enumValueOrDefault(prefs[Keys.ART_SHAPE], def.artShape),
            artSizeDp = prefs[Keys.ART_SIZE] ?: def.artSizeDp,
            artBeatPulse = prefs[Keys.ART_PULSE] ?: def.artBeatPulse,
            scrubberStyle = enumValueOrDefault(prefs[Keys.SCRUBBER_STYLE], def.scrubberStyle),
            showVolumeSlider = prefs[Keys.SHOW_VOLUME] ?: def.showVolumeSlider,
            visualizerStyle = enumValueOrDefault(prefs[Keys.VISUALIZER_STYLE], def.visualizerStyle),
            showActionDock = prefs[Keys.SHOW_ACTION_DOCK] ?: def.showActionDock,

            cardThemeMode = enumValueOrDefault(prefs[Keys.CARD_THEME], def.cardThemeMode),
            backgroundAlpha = prefs[Keys.BG_ALPHA] ?: def.backgroundAlpha,
            glassBlurRadiusDp = prefs[Keys.GLASS_BLUR] ?: def.glassBlurRadiusDp,
            staticAccentArgb = prefs[Keys.STATIC_ACCENT] ?: def.staticAccentArgb,

            expandStiffness = prefs[Keys.EXP_STIFFNESS] ?: def.expandStiffness,
            expandDamping = prefs[Keys.EXP_DAMPING] ?: def.expandDamping,
            collapseStiffness = prefs[Keys.COL_STIFFNESS] ?: def.collapseStiffness,
            collapseDamping = prefs[Keys.COL_DAMPING] ?: def.collapseDamping,
            enableMetaballShader = prefs[Keys.ENABLE_METABALL] ?: def.enableMetaballShader,
            metaballThreshold = prefs[Keys.METABALL_THRESH] ?: def.metaballThreshold,

            whitelistedPackages = prefs[Keys.WHITELIST_PKGS] ?: def.whitelistedPackages,

            coexistenceMode = enumValueOrDefault(prefs[Keys.COEXIST_MODE], def.coexistenceMode),
            nativeStackOffsetYDp = prefs[Keys.NATIVE_STACK_Y] ?: def.nativeStackOffsetYDp,
            suppressNativeFocus = prefs[Keys.SUPPRESS_NATIVE] ?: def.suppressNativeFocus,
            onSingleTap = enumValueOrDefault(prefs[Keys.GESTURE_TAP], def.onSingleTap),
            onDoubleTap = enumValueOrDefault(prefs[Keys.GESTURE_DOUBLE], def.onDoubleTap),
            onLongPress = enumValueOrDefault(prefs[Keys.GESTURE_LONG], def.onLongPress),
            onSwipeLeft = enumValueOrDefault(prefs[Keys.GESTURE_LEFT], def.onSwipeLeft),
            onSwipeRight = enumValueOrDefault(prefs[Keys.GESTURE_RIGHT], def.onSwipeRight),
            onSwipeDown = enumValueOrDefault(prefs[Keys.GESTURE_DOWN], def.onSwipeDown)
        )
    }

    private suspend fun saveConfigToPreferences(cfg: IslandConfig) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.CUTOUT_OFFSET_X] = cfg.cutoutOffsetXPx
            prefs[Keys.CUTOUT_OFFSET_Y] = cfg.cutoutOffsetYPx
            prefs[Keys.CUTOUT_RADIUS] = cfg.cutoutRadiusPx
            prefs[Keys.COLLAPSED_W] = cfg.collapsedWidthDp
            prefs[Keys.COLLAPSED_H] = cfg.collapsedHeightDp
            prefs[Keys.RING_STYLE] = cfg.cutoutRingStyle.name

            prefs[Keys.EXPANDED_W] = cfg.expandedWidthDp
            prefs[Keys.EXPANDED_H] = cfg.expandedHeightDp
            prefs[Keys.EXP_TOP_RAD] = cfg.expandedTopRadiusDp
            prefs[Keys.EXP_BOT_RAD] = cfg.expandedBottomRadiusDp
            prefs[Keys.SHAPE_PRESET] = cfg.shapePreset.name
            prefs[Keys.EXP_PADDING] = cfg.expandedPaddingDp
            prefs[Keys.EXP_TOP_OFFSET] = cfg.expandedTopOffsetDp

            prefs[Keys.SHOW_ART] = cfg.showAlbumArt
            prefs[Keys.ART_SHAPE] = cfg.artShape.name
            prefs[Keys.ART_SIZE] = cfg.artSizeDp
            prefs[Keys.ART_PULSE] = cfg.artBeatPulse
            prefs[Keys.SCRUBBER_STYLE] = cfg.scrubberStyle.name
            prefs[Keys.SHOW_VOLUME] = cfg.showVolumeSlider
            prefs[Keys.VISUALIZER_STYLE] = cfg.visualizerStyle.name
            prefs[Keys.SHOW_ACTION_DOCK] = cfg.showActionDock

            prefs[Keys.CARD_THEME] = cfg.cardThemeMode.name
            prefs[Keys.BG_ALPHA] = cfg.backgroundAlpha
            prefs[Keys.GLASS_BLUR] = cfg.glassBlurRadiusDp
            prefs[Keys.STATIC_ACCENT] = cfg.staticAccentArgb

            prefs[Keys.EXP_STIFFNESS] = cfg.expandStiffness
            prefs[Keys.EXP_DAMPING] = cfg.expandDamping
            prefs[Keys.COL_STIFFNESS] = cfg.collapseStiffness
            prefs[Keys.COL_DAMPING] = cfg.collapseDamping
            prefs[Keys.ENABLE_METABALL] = cfg.enableMetaballShader
            prefs[Keys.METABALL_THRESH] = cfg.metaballThreshold

            prefs[Keys.WHITELIST_PKGS] = cfg.whitelistedPackages

            prefs[Keys.COEXIST_MODE] = cfg.coexistenceMode.name
            prefs[Keys.NATIVE_STACK_Y] = cfg.nativeStackOffsetYDp
            prefs[Keys.SUPPRESS_NATIVE] = cfg.suppressNativeFocus
            prefs[Keys.GESTURE_TAP] = cfg.onSingleTap.name
            prefs[Keys.GESTURE_DOUBLE] = cfg.onDoubleTap.name
            prefs[Keys.GESTURE_LONG] = cfg.onLongPress.name
            prefs[Keys.GESTURE_LEFT] = cfg.onSwipeLeft.name
            prefs[Keys.GESTURE_RIGHT] = cfg.onSwipeRight.name
            prefs[Keys.GESTURE_DOWN] = cfg.onSwipeDown.name
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T {
        if (name == null) return default
        return try {
            enumValueOf<T>(name)
        } catch (_: IllegalArgumentException) {
            default
        }
    }

    private object Keys {
        val CUTOUT_OFFSET_X = floatPreferencesKey("cutout_offset_x")
        val CUTOUT_OFFSET_Y = floatPreferencesKey("cutout_offset_y")
        val CUTOUT_RADIUS = floatPreferencesKey("cutout_radius")
        val COLLAPSED_W = floatPreferencesKey("collapsed_w")
        val COLLAPSED_H = floatPreferencesKey("collapsed_h")
        val RING_STYLE = stringPreferencesKey("ring_style")

        val EXPANDED_W = floatPreferencesKey("expanded_w")
        val EXPANDED_H = floatPreferencesKey("expanded_h")
        val EXP_TOP_RAD = floatPreferencesKey("exp_top_rad")
        val EXP_BOT_RAD = floatPreferencesKey("exp_bot_rad")
        val SHAPE_PRESET = stringPreferencesKey("shape_preset")
        val EXP_PADDING = floatPreferencesKey("exp_padding")
        val EXP_TOP_OFFSET = floatPreferencesKey("exp_top_offset")

        val SHOW_ART = booleanPreferencesKey("show_art")
        val ART_SHAPE = stringPreferencesKey("art_shape")
        val ART_SIZE = floatPreferencesKey("art_size")
        val ART_PULSE = booleanPreferencesKey("art_pulse")
        val SCRUBBER_STYLE = stringPreferencesKey("scrubber_style")
        val SHOW_VOLUME = booleanPreferencesKey("show_volume")
        val VISUALIZER_STYLE = stringPreferencesKey("visualizer_style")
        val SHOW_ACTION_DOCK = booleanPreferencesKey("show_action_dock")

        val CARD_THEME = stringPreferencesKey("card_theme")
        val BG_ALPHA = floatPreferencesKey("bg_alpha")
        val GLASS_BLUR = floatPreferencesKey("glass_blur")
        val STATIC_ACCENT = intPreferencesKey("static_accent")

        val EXP_STIFFNESS = floatPreferencesKey("exp_stiffness")
        val EXP_DAMPING = floatPreferencesKey("exp_damping")
        val COL_STIFFNESS = floatPreferencesKey("col_stiffness")
        val COL_DAMPING = floatPreferencesKey("col_damping")
        val ENABLE_METABALL = booleanPreferencesKey("enable_metaball")
        val METABALL_THRESH = floatPreferencesKey("metaball_thresh")

        val WHITELIST_PKGS = stringSetPreferencesKey("whitelist_pkgs")

        val COEXIST_MODE = stringPreferencesKey("coexist_mode")
        val NATIVE_STACK_Y = floatPreferencesKey("native_stack_y")
        val SUPPRESS_NATIVE = booleanPreferencesKey("suppress_native")
        val GESTURE_TAP = stringPreferencesKey("gesture_tap")
        val GESTURE_DOUBLE = stringPreferencesKey("gesture_double")
        val GESTURE_LONG = stringPreferencesKey("gesture_long")
        val GESTURE_LEFT = stringPreferencesKey("gesture_left")
        val GESTURE_RIGHT = stringPreferencesKey("gesture_right")
        val GESTURE_DOWN = stringPreferencesKey("gesture_down")
    }

    companion object {
        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context).also { INSTANCE = it }
            }
        }
    }
}
