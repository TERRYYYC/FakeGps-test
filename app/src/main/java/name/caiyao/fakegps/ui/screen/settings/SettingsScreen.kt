package name.caiyao.fakegps.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import name.caiyao.fakegps.data.SpoofSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val spoofMode by vm.spoofMode.collectAsState()
    val hourStart by vm.activeHourStart.collectAsState()
    val hourEnd by vm.activeHourEnd.collectAsState()

    var showModeDialog by remember { mutableStateOf(false) }
    var showHourStartDialog by remember { mutableStateOf(false) }
    var showHourEndDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- Hook 配置 ---
            SectionHeader("Hook 配置")
            ListItem(
                headlineContent = { Text("伪装模式") },
                supportingContent = { Text(modeDisplayName(spoofMode)) },
                modifier = Modifier.clickable { showModeDialog = true },
            )
            AnimatedVisibility(visible = spoofMode == SpoofSettings.MODE_TIME_BASED) {
                Column {
                    ListItem(
                        headlineContent = { Text("开始时间") },
                        supportingContent = { Text("%02d:00".format(hourStart)) },
                        modifier = Modifier.clickable { showHourStartDialog = true },
                    )
                    ListItem(
                        headlineContent = { Text("结束时间") },
                        supportingContent = { Text("%02d:00".format(hourEnd)) },
                        modifier = Modifier.clickable { showHourEndDialog = true },
                    )
                }
            }
            ListItem(
                headlineContent = { Text("刷新间隔") },
                supportingContent = { Text("60 秒") },
            )
            HorizontalDivider()

            // --- 外观 ---
            SectionHeader("外观")
            ListItem(
                headlineContent = { Text("主题") },
                supportingContent = { Text("跟随系统") },
            )
            HorizontalDivider()

            // --- 数据 ---
            SectionHeader("数据管理")
            ListItem(
                headlineContent = { Text("导出档案") },
                supportingContent = { Text("导出所有档案为 JSON") },
            )
            ListItem(
                headlineContent = { Text("导入档案") },
                supportingContent = { Text("从 JSON 文件导入") },
            )
            ListItem(
                headlineContent = { Text("清空所有档案") },
                supportingContent = { Text("删除所有已保存的档案") },
            )
            HorizontalDivider()

            // --- 关于 ---
            SectionHeader("关于")
            ListItem(
                headlineContent = { Text("版本") },
                supportingContent = { Text("3.0.0") },
            )
            ListItem(
                headlineContent = { Text("GitHub") },
                supportingContent = { Text("TERRYYYC/FakeGps-test") },
            )
        }
    }

    // Mode selection dialog
    if (showModeDialog) {
        val options = listOf(
            SpoofSettings.MODE_ALWAYS_ON to "始终开启",
            SpoofSettings.MODE_TIME_BASED to "按时段",
            SpoofSettings.MODE_OFF to "关闭",
        )
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("伪装模式") },
            text = {
                Column {
                    for ((value, label) in options) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setSpoofMode(value)
                                    showModeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = spoofMode == value,
                                onClick = {
                                    vm.setSpoofMode(value)
                                    showModeDialog = false
                                },
                            )
                            Text(
                                text = label,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Text(
                        text = "始终开启：忽略时段，始终使用第一条档案\n" +
                                "按时段：仅在配置的时段内伪装\n" +
                                "关闭：透传真实设备信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) { Text("取消") }
            },
        )
    }

    // Hour picker dialogs
    if (showHourStartDialog) {
        HourPickerDialog(
            title = "开始时间",
            currentHour = hourStart,
            onConfirm = { vm.setActiveHourStart(it); showHourStartDialog = false },
            onDismiss = { showHourStartDialog = false },
        )
    }

    if (showHourEndDialog) {
        HourPickerDialog(
            title = "结束时间",
            currentHour = hourEnd,
            onConfirm = { vm.setActiveHourEnd(it); showHourEndDialog = false },
            onDismiss = { showHourEndDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun HourPickerDialog(
    title: String,
    currentHour: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(currentHour.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "%02d:00".format(selected.roundToInt()),
                    style = MaterialTheme.typography.displaySmall,
                )
                Slider(
                    value = selected,
                    onValueChange = { selected = it },
                    valueRange = 0f..23f,
                    steps = 22,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("00:00", style = MaterialTheme.typography.bodySmall)
                    Text("23:00", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.roundToInt()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun modeDisplayName(mode: String): String = when (mode) {
    SpoofSettings.MODE_ALWAYS_ON -> "始终开启"
    SpoofSettings.MODE_TIME_BASED -> "按时段"
    SpoofSettings.MODE_OFF -> "关闭"
    else -> mode
}
