package com.mecon.desktop.ui.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mecon.audio.engine.AudioEngine
import com.mecon.audio.model.SoundFontId
import com.mecon.audio.model.SoundFontInfo
import com.mecon.audio.soundfont.SoundFontManager
import com.mecon.audio.soundfont.SoundFontLoadState
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.theme.MeconColors
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 音频设置对话框 - 管理音色库和乐器配置
 */
@Composable
fun AudioSettingsDialog(
    audioEngine: AudioEngine,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val soundFontManager = audioEngine.soundFontManager

    val availableSoundFonts by soundFontManager.availableSoundFonts.collectAsState()
    val loadedSoundFonts by soundFontManager.loadedSoundFonts.collectAsState()
    val defaultSoundFont by soundFontManager.defaultSoundFont.collectAsState()
    val soundFontLoadState by soundFontManager.loadState.collectAsState()
    val tempoMultiplier by audioEngine.tempoMultiplier.collectAsState()
    val masterVolume by audioEngine.masterVolume.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(600.dp)
                .heightIn(max = 700.dp),
            shape = RoundedCornerShape(12.dp),
            color = MeconColors.Surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // 标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = i18n("dialog.audio.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MeconColors.IconDefault
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 错误提示
                errorMessage?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF7f1d1d),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = Color(0xFFfca5a5),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = error,
                                color = Color(0xFFfca5a5),
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { errorMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = Color(0xFFfca5a5),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Tab 导航
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MeconColors.Background,
                    contentColor = Color.White
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(i18n("dialog.audio.tab.soundfonts")) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(i18n("dialog.audio.tab.playback")) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab 内容
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> SoundFontTab(
                            availableSoundFonts = availableSoundFonts,
                            loadedSoundFonts = loadedSoundFonts,
                            defaultSoundFont = defaultSoundFont,
                            isLoading = isLoading || soundFontLoadState is SoundFontLoadState.Loading,
                            loadingSoundFontName = (soundFontLoadState as? SoundFontLoadState.Loading)?.soundFontName,
                            onAddSoundFont = { filePath ->
                                coroutineScope.launch {
                                    isLoading = true
                                    errorMessage = null
                                    soundFontManager.addSoundFont(filePath)
                                        .onSuccess { info ->
                                            // Auto-load the added SoundFont
                                            soundFontManager.loadSoundFont(info.id)
                                                .onFailure { error ->
                                                    errorMessage = error.message
                                                }
                                        }
                                        .onFailure { error ->
                                            errorMessage = error.message
                                        }
                                    isLoading = false
                                }
                            },
                            onLoadSoundFont = { soundFontId ->
                                coroutineScope.launch {
                                    isLoading = true
                                    errorMessage = null
                                    soundFontManager.loadSoundFont(soundFontId)
                                        .onFailure { error ->
                                            errorMessage = error.message
                                        }
                                    isLoading = false
                                }
                            },
                            onUnloadSoundFont = { soundFontId ->
                                coroutineScope.launch {
                                    soundFontManager.unloadSoundFont(soundFontId)
                                }
                            },
                            onSetDefault = { soundFontId ->
                                soundFontManager.setDefaultSoundFont(soundFontId)
                            },
                            onScanDirectory = { directoryPath ->
                                coroutineScope.launch {
                                    isLoading = true
                                    errorMessage = null
                                    soundFontManager.scanDirectory(directoryPath)
                                        .onFailure { error ->
                                            errorMessage = error.message
                                        }
                                    isLoading = false
                                }
                            }
                        )
                        1 -> PlaybackTab(
                            tempoMultiplier = tempoMultiplier,
                            masterVolume = masterVolume,
                            onTempoChange = { audioEngine.setTempoMultiplier(it) },
                            onVolumeChange = { audioEngine.setMasterVolume(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MeconColors.Primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(i18n("dialog.audio.close"))
                    }
                }
            }
        }
    }
}

/**
 * 音色库管理 Tab
 */
@Composable
private fun SoundFontTab(
    availableSoundFonts: List<SoundFontInfo>,
    loadedSoundFonts: Set<SoundFontId>,
    defaultSoundFont: SoundFontInfo?,
    isLoading: Boolean,
    loadingSoundFontName: String?,
    onAddSoundFont: (String) -> Unit,
    onLoadSoundFont: (SoundFontId) -> Unit,
    onUnloadSoundFont: (SoundFontId) -> Unit,
    onSetDefault: (SoundFontId?) -> Unit,
    onScanDirectory: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val file = showSoundFontFileDialog()
                    file?.let { onAddSoundFont(it.absolutePath) }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MeconColors.IconDefault
                ),
                border = BorderStroke(1.dp, MeconColors.Primary)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(i18n("dialog.audio.soundfont.add"))
            }

            OutlinedButton(
                onClick = {
                    val dir = showDirectoryDialog()
                    dir?.let { onScanDirectory(it.absolutePath) }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MeconColors.IconDefault
                ),
                border = BorderStroke(1.dp, MeconColors.BorderLight)
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(i18n("dialog.audio.soundfont.scan"))
            }

            if (isLoading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MeconColors.Primary,
                    strokeWidth = 2.dp
                )
                loadingSoundFontName?.let { name ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        i18n("toolbar.soundfontLoading").replace("{name}", name),
                        color = MeconColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 音色库列表
        if (availableSoundFonts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MeconColors.BorderLight
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = i18n("dialog.audio.soundfont.empty"),
                        color = MeconColors.TextMuted,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = i18n("dialog.audio.soundfont.hint"),
                        color = MeconColors.BorderLight,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableSoundFonts, key = { it.id.value }) { soundFont ->
                    SoundFontItem(
                        soundFont = soundFont,
                        isLoaded = soundFont.id in loadedSoundFonts,
                        isDefault = defaultSoundFont?.id == soundFont.id,
                        onLoad = { onLoadSoundFont(soundFont.id) },
                        onUnload = { onUnloadSoundFont(soundFont.id) },
                        onSetDefault = { onSetDefault(soundFont.id) }
                    )
                }
            }
        }
    }
}

/**
 * 单个音色库项
 */
@Composable
private fun SoundFontItem(
    soundFont: SoundFontInfo,
    isLoaded: Boolean,
    isDefault: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onSetDefault: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isLoaded) Color(0xFF1e3a5f) else MeconColors.Background,
        shape = RoundedCornerShape(8.dp),
        border = if (isDefault) BorderStroke(1.dp, MeconColors.Primary) else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isLoaded) MeconColors.Primary else MeconColors.SurfaceLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isLoaded) Icons.Default.MusicNote else Icons.Default.MusicOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // 信息
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = soundFont.name,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    if (isDefault) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MeconColors.Primary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = i18n("dialog.audio.soundfont.default"),
                                color = Color.White,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${soundFont.format.extension.uppercase()} · ${soundFont.fileSizeFormatted}",
                    color = MeconColors.TextMuted,
                    fontSize = 12.sp
                )
                if (soundFont.presetCount > 0) {
                    Text(
                        text = "${soundFont.presetCount} ${i18n("dialog.audio.soundfont.presets")}",
                        color = MeconColors.TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            // 操作按钮
            Row {
                if (!isLoaded) {
                    IconButton(onClick = onLoad) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Load",
                            tint = MeconColors.IconDefault
                        )
                    }
                } else {
                    if (!isDefault) {
                        IconButton(onClick = onSetDefault) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "Set as default",
                                tint = Color(0xFFfbbf24)
                            )
                        }
                    }
                    IconButton(onClick = onUnload) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Unload",
                            tint = MeconColors.IconDefault
                        )
                    }
                }
            }
        }
    }
}

/**
 * 播放设置 Tab
 */
@Composable
private fun PlaybackTab(
    tempoMultiplier: Float,
    masterVolume: Float,
    onTempoChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 速度控制
        SettingSection(
            title = i18n("dialog.audio.playback.tempo"),
            icon = Icons.Default.Speed
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(tempoMultiplier * 100).toInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 24.sp
                    )
                    Row {
                        TempoPresetButton("50%", 0.5f, tempoMultiplier, onTempoChange)
                        TempoPresetButton("75%", 0.75f, tempoMultiplier, onTempoChange)
                        TempoPresetButton("100%", 1.0f, tempoMultiplier, onTempoChange)
                        TempoPresetButton("125%", 1.25f, tempoMultiplier, onTempoChange)
                        TempoPresetButton("150%", 1.5f, tempoMultiplier, onTempoChange)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = tempoMultiplier,
                    onValueChange = onTempoChange,
                    valueRange = 0.25f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = MeconColors.Primary,
                        activeTrackColor = MeconColors.Primary,
                        inactiveTrackColor = MeconColors.SurfaceLight
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("25%", color = MeconColors.TextMuted, fontSize = 12.sp)
                    Text("200%", color = MeconColors.TextMuted, fontSize = 12.sp)
                }
            }
        }

        // 音量控制
        SettingSection(
            title = i18n("dialog.audio.playback.volume"),
            icon = Icons.Default.VolumeUp
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${(masterVolume * 100).toInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 24.sp
                    )
                    Icon(
                        when {
                            masterVolume == 0f -> Icons.Default.VolumeOff
                            masterVolume < 0.5f -> Icons.Default.VolumeDown
                            else -> Icons.Default.VolumeUp
                        },
                        contentDescription = null,
                        tint = MeconColors.Primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = masterVolume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = MeconColors.Primary,
                        activeTrackColor = MeconColors.Primary,
                        inactiveTrackColor = MeconColors.SurfaceLight
                    )
                )
            }
        }
    }
}

@Composable
private fun TempoPresetButton(
    label: String,
    value: Float,
    currentValue: Float,
    onClick: (Float) -> Unit
) {
    val isSelected = (currentValue - value).let { it > -0.01f && it < 0.01f }
    Surface(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clickable { onClick(value) },
        color = if (isSelected) MeconColors.SelectedSurface else MeconColors.SurfaceLight,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) MeconColors.SelectedIcon else MeconColors.IconDefault,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SettingSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MeconColors.Primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = MeconColors.TextSecondary,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MeconColors.Background,
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

/**
 * 显示 SoundFont 文件选择对话框
 */
private fun showSoundFontFileDialog(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Select SoundFont File"
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = false
        addChoosableFileFilter(
            FileNameExtensionFilter("SoundFont Files (*.sf2, *.sf3)", "sf2", "SF2", "sf3", "SF3")
        )
    }

    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}

/**
 * 显示目录选择对话框
 */
private fun showDirectoryDialog(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Select SoundFont Directory"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }

    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}
