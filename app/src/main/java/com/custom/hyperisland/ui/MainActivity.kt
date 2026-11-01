package com.custom.hyperisland.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.hyperisland.data.*
import com.custom.hyperisland.overlay.DynamicIslandSurface
import com.custom.hyperisland.overlay.IslandOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class AppEntry(val packageName: String, val label: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository.getInstance(this)

        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, IslandOverlayService::class.java))
        }

        setContent {
            val darkColorScheme = darkColorScheme(
                primary = Color(0xFF00E5FF),
                secondary = Color(0xFF7C4DFF),
                background = Color(0xFF090A0F),
                surface = Color(0xFF141622),
                surfaceVariant = Color(0xFF1E2233)
            )

            MaterialTheme(colorScheme = darkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HyperIslandSettingsScreen(repository = repository)
                }
            }
        }
    }
}

@Composable
fun HyperIslandSettingsScreen(repository: SettingsRepository) {
    val config by repository.config.collectAsState()
    val liveRuntimeState by IslandRuntimeBus.runtimeState.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(1) }
    var previewModeIndex by remember { mutableIntStateOf(1) }

    val tabs = listOf(
        "Свернутый остров",
        "Раскрытый остров",
        "Физика и Анимации",
        "Приложения и Фильтры",
        "HyperOS и Система"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        StickyLivePreviewHeader(
            config = config,
            liveState = liveRuntimeState,
            previewModeIndex = previewModeIndex,
            onPreviewModeChange = { previewModeIndex = it }
        )

        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 16.dp,
            containerColor = Color(0xFF10121C),
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTabIndex) {
                0 -> CollapsedIslandTab(config = config, onUpdate = repository::updateConfig)
                1 -> ExpandedIslandTab(config = config, onUpdate = repository::updateConfig)
                2 -> PhysicsAndShadersTab(config = config, onUpdate = repository::updateConfig)
                3 -> AppsWhitelistTab(config = config, onUpdate = repository::updateConfig)
                4 -> HyperOsSystemTab(config = config, onUpdate = repository::updateConfig)
            }
        }
    }
}

@Composable
private fun StickyLivePreviewHeader(
    config: IslandConfig,
    liveState: IslandRuntimeState,
    previewModeIndex: Int,
    onPreviewModeChange: (Int) -> Unit
) {
    val mockMedia = remember {
        MediaSlice(
            packageName = "com.spotify.music",
            title = "Hollywood Highway",
            artist = "FORTUNA 812",
            albumArt = null,
            isPlaying = true,
            durationMs = 180_000L,
            positionMs = 74_000L,
            dominantColor = Color(config.staticAccentArgb),
            darkSurfaceColor = Color(0xFF151828)
        )
    }

    val mockNotification = remember {
        NotificationSlice(
            key = "preview_key",
            packageName = "org.telegram.messenger",
            appName = "Telegram",
            title = "Telegram • На предпросмотре",
            message = "Кастомизация геометрии и шейдеров применяется мгновенно в 120 Гц",
            contentIntent = null,
            actions = emptyList(),
            accentColor = Color(0xFF00E5FF)
        )
    }

    val synthesizedState = remember(previewModeIndex, config, liveState) {
        when (previewModeIndex) {
            0 -> liveState.copy(
                mode = IslandDisplayMode.CompactPill(showDetachedDroplet = config.enableMetaballShader),
                media = liveState.media ?: mockMedia,
                notification = null
            )
            1 -> liveState.copy(
                mode = IslandDisplayMode.ExpandedCard,
                media = liveState.media ?: mockMedia.copy(dominantColor = Color(config.staticAccentArgb)),
                notification = null
            )
            2 -> liveState.copy(
                mode = IslandDisplayMode.ExpandedCard,
                media = null,
                notification = liveState.notification ?: mockNotification
            )
            else -> liveState.copy(mode = IslandDisplayMode.IdleCutout)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0F18))
            .padding(top = 36.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            val modes = listOf("Свернут", "Раскрыт (Медиа)", "Уведомление", "Вырез")
            modes.forEachIndexed { idx, label ->
                FilterChip(
                    selected = previewModeIndex == idx,
                    onClick = { onPreviewModeChange(idx) },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp, max = 260.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            DynamicIslandSurface(
                config = config,
                state = synthesizedState,
                isPreviewMode = true,
                onBoundsChanged = { _, _, _, _ -> },
                onLaunchFreeform = {}
            )
        }
    }
}

@Composable
private fun CollapsedIslandTab(config: IslandConfig, onUpdate: ((IslandConfig) -> IslandConfig) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard("Калибровка выреза фронтальной камеры (Poco M8 5G)") {
            LabeledSlider("Смещение по оси X", config.cutoutOffsetXPx, -60f..60f, "px") { v -> onUpdate { it.copy(cutoutOffsetXPx = v) } }
            LabeledSlider("Смещение по оси Y", config.cutoutOffsetYPx, -20f..60f, "px") { v -> onUpdate { it.copy(cutoutOffsetYPx = v) } }
            LabeledSlider("Радиус маски выреза", config.cutoutRadiusPx, 20f..60f, "px") { v -> onUpdate { it.copy(cutoutRadiusPx = v) } }
        }
        SectionCard("Геометрия свернутой капсулы") {
            LabeledSlider("Ширина капсулы", config.collapsedWidthDp, 96f..220f, "dp") { v -> onUpdate { it.copy(collapsedWidthDp = v) } }
            LabeledSlider("Высота капсулы", config.collapsedHeightDp, 28f..52f, "dp") { v -> onUpdate { it.copy(collapsedHeightDp = v) } }
        }
        SectionCard("Кольцо вокруг камеры в режиме покоя") {
            EnumSelectorGroup(CutoutRingStyle.entries, config.cutoutRingStyle, { it.title }) { style -> onUpdate { it.copy(cutoutRingStyle = style) } }
        }
    }
}

@Composable
private fun ExpandedIslandTab(config: IslandConfig, onUpdate: ((IslandConfig) -> IslandConfig) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard("1. Геометрия и морфинг формы") {
            LabeledSlider("Раскрытая ширина", config.expandedWidthDp, 280f..390f, "dp") { v -> onUpdate { it.copy(expandedWidthDp = v) } }
            LabeledSlider("Раскрытая высота", config.expandedHeightDp, 120f..320f, "dp") { v -> onUpdate { it.copy(expandedHeightDp = v) } }
            LabeledSlider("Радиус верхних углов", config.expandedTopRadiusDp, 16f..42f, "dp") { v -> onUpdate { it.copy(expandedTopRadiusDp = v) } }
            LabeledSlider("Радиус нижних углов", config.expandedBottomRadiusDp, 16f..52f, "dp") { v -> onUpdate { it.copy(expandedBottomRadiusDp = v) } }
            LabeledSlider("Внутренний отступ", config.expandedPaddingDp, 8f..24f, "dp") { v -> onUpdate { it.copy(expandedPaddingDp = v) } }
            LabeledSlider("Вертикальный отступ", config.expandedTopOffsetDp, 0f..36f, "dp") { v -> onUpdate { it.copy(expandedTopOffsetDp = v) } }
            Text("Пресет формы:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            EnumSelectorGroup(ShapePreset.entries, config.shapePreset, { it.title }) { preset -> onUpdate { it.copy(shapePreset = preset) } }
        }
        SectionCard("2. Модульный конструктор виджетов") {
            ToggleRow("Отображать обложку трека", config.showAlbumArt) { v -> onUpdate { it.copy(showAlbumArt = v) } }
            if (config.showAlbumArt) {
                LabeledSlider("Размер обложки", config.artSizeDp, 44f..72f, "dp") { v -> onUpdate { it.copy(artSizeDp = v) } }
                ToggleRow("Пульсация обложки", config.artBeatPulse) { v -> onUpdate { it.copy(artBeatPulse = v) } }
                EnumSelectorGroup(ArtShape.entries, config.artShape, { it.title }) { s -> onUpdate { it.copy(artShape = s) } }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Text("Стиль прогресс-бара:", color = Color.White, fontSize = 13.sp)
            EnumSelectorGroup(ScrubberStyle.entries, config.scrubberStyle, { it.title }) { s -> onUpdate { it.copy(scrubberStyle = s) } }
            ToggleRow("Слайдер громкости с виброоткликом", config.showVolumeSlider) { v -> onUpdate { it.copy(showVolumeSlider = v) } }
            ToggleRow("Панель действий (Action Dock)", config.showActionDock) { v -> onUpdate { it.copy(showActionDock = v) } }
            Text("Стиль визуализатора:", color = Color.White, fontSize = 13.sp)
            EnumSelectorGroup(VisualizerStyle.entries, config.visualizerStyle, { it.title }) { s -> onUpdate { it.copy(visualizerStyle = s) } }
        }
        SectionCard("3. Стекломорфизм и цвета") {
            LabeledSlider("Непрозрачность фона", config.backgroundAlpha * 100f, 60f..100f, "%") { v -> onUpdate { it.copy(backgroundAlpha = v / 100f) } }
            LabeledSlider("Радиус аппаратного блюра", config.glassBlurRadiusDp, 0f..35f, "dp") { v -> onUpdate { it.copy(glassBlurRadiusDp = v) } }
            EnumSelectorGroup(CardThemeMode.entries, config.cardThemeMode, { it.title }) { m -> onUpdate { it.copy(cardThemeMode = m) } }
        }
    }
}

@Composable
private fun PhysicsAndShadersTab(config: IslandConfig, onUpdate: ((IslandConfig) -> IslandConfig) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard("Фаза раскрытия (Expand Spring)") {
            LabeledSlider("Жесткость пружины", config.expandStiffness, 180f..1000f, "") { v -> onUpdate { it.copy(expandStiffness = v) } }
            LabeledSlider("Затухание", config.expandDamping, 0.35f..1.0f, "") { v -> onUpdate { it.copy(expandDamping = v) } }
        }
        SectionCard("Фаза схлопывания (Collapse Spring)") {
            LabeledSlider("Жесткость втягивания", config.collapseStiffness, 300f..1400f, "") { v -> onUpdate { it.copy(collapseStiffness = v) } }
            LabeledSlider("Демпфирование", config.collapseDamping, 0.65f..1.0f, "") { v -> onUpdate { it.copy(collapseDamping = v) } }
        }
        SectionCard("AGSL Metaball Shader") {
            ToggleRow("Жидкостное слияние капли", config.enableMetaballShader) { v -> onUpdate { it.copy(enableMetaballShader = v) } }
            LabeledSlider("Порог натяжения (Threshold)", config.metaballThreshold, 0.20f..0.75f, "") { v -> onUpdate { it.copy(metaballThreshold = v) } }
        }
    }
}

@Composable
private fun AppsWhitelistTab(config: IslandConfig, onUpdate: ((IslandConfig) -> IslandConfig) -> Unit) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            installedApps = pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("miui") }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    val filtered = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps else installedApps.filter { it.label.contains(searchQuery, true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Поиск приложения") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                val isChecked = app.packageName in config.whitelistedPackages
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            onUpdate { curr ->
                                val nextSet = curr.whitelistedPackages.toMutableSet()
                                if (isChecked) nextSet.remove(app.packageName) else nextSet.add(app.packageName)
                                curr.copy(whitelistedPackages = nextSet)
                            }
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(app.packageName, color = Color.Gray, fontSize = 11.sp)
                    }
                    Checkbox(checked = isChecked, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun HyperOsSystemTab(config: IslandConfig, onUpdate: ((IslandConfig) -> IslandConfig) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard("Симбиоз с HyperOS 3") {
            EnumSelectorGroup(CoexistenceMode.entries, config.coexistenceMode, { it.title }) { m -> onUpdate { it.copy(coexistenceMode = m) } }
            LabeledSlider("Отступ второго яруса", config.nativeStackOffsetYDp, 32f..76f, "dp") { v -> onUpdate { it.copy(nativeStackOffsetYDp = v) } }
            ToggleRow("Подавлять нативный фокус HyperOS", config.suppressNativeFocus) { suppress ->
                onUpdate { it.copy(suppressNativeFocus = suppress) }
                try {
                    Settings.Secure.putInt(context.contentResolver, "miui_focus_notification_enabled", if (suppress) 0 else 1)
                } catch (_: SecurityException) {}
            }
        }
        SectionCard("Назначение жестов") {
            Text("Одиночный тап:", color = Color.Gray, fontSize = 12.sp)
            EnumSelectorGroup(IslandGestureAction.entries, config.onSingleTap, { it.title }) { a -> onUpdate { it.copy(onSingleTap = a) } }
            Text("Свайп вниз:", color = Color.Gray, fontSize = 12.sp)
            EnumSelectorGroup(IslandGestureAction.entries, config.onSwipeDown, { it.title }) { a -> onUpdate { it.copy(onSwipeDown = a) } }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onValueChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 13.sp)
            val formatted = if (range.endInclusive <= 2f) String.format("%.2f %s", value, unit) else "${value.roundToInt()} $unit"
            Text(formatted, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> EnumSelectorGroup(entries: List<T>, selected: T, labelProvider: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (item == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(item) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = item == selected, onClick = { onSelect(item) })
                Spacer(modifier = Modifier.width(6.dp))
                Text(labelProvider(item), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
