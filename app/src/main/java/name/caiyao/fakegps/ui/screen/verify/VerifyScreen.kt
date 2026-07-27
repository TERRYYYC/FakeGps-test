package name.caiyao.fakegps.ui.screen.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.verify.FieldReport
import name.caiyao.fakegps.verify.FieldVerdict
import name.caiyao.fakegps.verify.ObservationScope
import name.caiyao.fakegps.verify.VerificationStatus

private enum class RowFilter(val label: String) {
    CONFIGURED("仅已配置"),
    ALL("全部字段"),
}

/**
 * Reconciliation view: for every field — what was configured, what the app actually reads back, and
 * whether those agree.
 *
 * Replaces a flat dump of device readings, which could not answer the only question that matters
 * ("did my configuration take effect?") because it never showed the configured value at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(
    onBack: () -> Unit,
    vm: VerifyViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var filter by remember { mutableStateOf(RowFilter.CONFIGURED) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("伪装验证") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新验证")
                    }
                },
            )
        },
    ) { innerPadding ->
        val report = state.report
        if (state.loading && report == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "verdict") { VerdictCard(state) }
            item(key = "scope") { ScopeCard(state) }
            item(key = "payload") { PayloadCard(state) }

            if (state.notes.isNotEmpty()) {
                item(key = "notes") { NotesCard(state.notes) }
            }

            item(key = "filter") {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (f in RowFilter.entries) {
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                        )
                    }
                }
            }

            for (group in report?.groups.orEmpty()) {
                val rows = group.fields.filter { it.visibleUnder(filter) }
                if (rows.isEmpty()) continue

                item(key = "h_${group.category}") {
                    Text(
                        text = group.category,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                item(key = "c_${group.category}") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            rows.forEachIndexed { i, f ->
                                FieldRow(f, state.scope)
                                if (i < rows.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }

            item(key = "tail") { Box(Modifier.padding(bottom = 24.dp)) {} }
        }
    }
}

private fun FieldReport.visibleUnder(filter: RowFilter): Boolean = when (filter) {
    RowFilter.ALL -> true
    // A passthrough row reflects no decision the user made, so it is noise while checking a config.
    RowFilter.CONFIGURED -> verdict != FieldVerdict.PASSTHROUGH
}

// -- header cards -------------------------------------------------------------------------------

@Composable
private fun VerdictCard(state: VerifyUiState) {
    val summary = state.report?.summary
    val spoofingOff = state.mode == SpoofSettings.MODE_OFF

    val headline: String
    val detail: String
    val tone: Color
    when {
        spoofingOff -> {
            headline = "伪装已关闭"
            detail = "当前模式为「关闭」，hook 会原样透传真实设备信息。到「设置 → 伪装模式」开启后再验证。"
            tone = MaterialTheme.colorScheme.onSurfaceVariant
        }
        summary == null -> {
            headline = "无法验证"
            detail = "未能读取配置。"
            tone = MaterialTheme.colorScheme.error
        }
        else -> when (summary.status) {
            VerificationStatus.EFFECTIVE -> {
                headline = "伪装生效"
                detail = "${summary.spoofed} 个字段读回值与配置一致。"
                tone = MaterialTheme.colorScheme.primary
            }
            VerificationStatus.FAILING -> {
                headline = "${summary.mismatch} 个字段未生效"
                detail = "这些字段配置了值，但应用读到的是别的内容。可能原因：" +
                    "配置没送达 hook、该字段的 hook 未覆盖" +
                    // A self-hooked build still needs its own package inside the framework's scope.
                    // Without this hint the most common innocent cause reads as a code defect.
                    if (state.scope == ObservationScope.SELF_HOOKED)
                        "，或本应用未加入 Vector/LSPosed 的作用域（自我 hook 未激活）。"
                    else "。"
                tone = MaterialTheme.colorScheme.error
            }
            VerificationStatus.INCONCLUSIVE -> {
                headline = "本页无法验证"
                detail = "配置了 ${summary.configuredCount} 个字段，但本进程一个都读不回来，" +
                    "因此既不能证明生效、也不能证明失败。"
                tone = MaterialTheme.colorScheme.onSurfaceVariant
            }
            VerificationStatus.NOTHING_CONFIGURED -> {
                headline = "当前档案没有配置任何字段"
                detail = "所有字段都会透传真实值。到档案编辑页填入至少一个字段再来验证。"
                tone = MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(headline, style = MaterialTheme.typography.titleLarge, color = tone)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary != null && !spoofingOff) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CountPill("已生效", summary.spoofed, verdictColor(FieldVerdict.SPOOFED))
                    CountPill("未生效", summary.mismatch, verdictColor(FieldVerdict.MISMATCH))
                    CountPill("读不到", summary.unobservable, verdictColor(FieldVerdict.UNOBSERVABLE))
                    CountPill("透传", summary.passthrough, verdictColor(FieldVerdict.PASSTHROUGH))
                }
            }
        }
    }
}

@Composable
private fun ScopeCard(state: VerifyUiState) {
    val text = when (state.scope) {
        ObservationScope.SELF_HOOKED ->
            "调试构建：本应用已 hook 自身进程，所以这里的读回值可以直接证明 hook 是否生效（受控探针模式）。"
        ObservationScope.REAL_BASELINE ->
            "正式构建不会 hook 自己的进程 —— 否则配置界面会把伪造值当成真值显示回来。" +
                "因此本页读到的是本机真实值，不能用来判断目标 App 内的 hook 是否生效；" +
                "它的用途是给你一份真实基线，好挑一个明显区别于真实网络的值。"
    }
    InfoCard(title = "这一页能证明什么", body = text)
}

@Composable
private fun PayloadCard(state: VerifyUiState) {
    val lines = mutableListOf<Pair<String, String>>()
    lines += "伪装模式" to modeLabel(state.mode)

    when (val p = state.payload) {
        is PayloadStatus.NeverPublished ->
            lines += "配置通道" to "从未发布过配置 — hook 无配置可用"
        is PayloadStatus.Malformed ->
            lines += "配置通道" to "payload 解析失败 — hook 将保留上一次可用配置"
        is PayloadStatus.Ok -> {
            lines += "已送达 hook 的字段数" to "${p.fieldCount}"
            lines += "schemaVersion" to
                if (p.compatible) "${p.schemaVersion}（兼容）"
                else "${p.schemaVersion} — hook 期望 ${ConfigPrefsSync.SCHEMA_VERSION}，会拒绝此配置"
        }
    }
    state.fingerprint?.let { lines += "配置指纹" to it }

    val unmapped = state.report?.unmappedPayloadColumns.orEmpty()
    if (unmapped.isNotEmpty()) {
        val preview = unmapped.take(3).joinToString() + if (unmapped.size > 3) "…" else ""
        lines += "界面未覆盖的字段" to "${unmapped.size} 个（$preview）"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("配置通道", style = MaterialTheme.typography.titleSmall)
            for ((k, v) in lines) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        k,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.45f),
                    )
                    Text(
                        v,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesCard(notes: List<String>) {
    InfoCard(
        title = "有些字段读不回来，原因如下",
        body = notes.joinToString("\n") { "· $it" },
    )
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -- field row ----------------------------------------------------------------------------------

@Composable
private fun FieldRow(f: FieldReport, scope: ObservationScope) {
    val observedLabel = if (scope == ObservationScope.SELF_HOOKED) "观测" else "真实"
    val observedValue = f.observed ?: f.baseline

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.spec.displayName, style = MaterialTheme.typography.bodyMedium)
            VerdictChip(f.verdict)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ValueSlot("配置", f.configured, Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
            ValueSlot(observedLabel, observedValue, Modifier.weight(1f))
        }

        if (f.ambiguous) {
            Text(
                text = "此值与真实值相同 — 即使 hook 生效也无法区分，建议改成明显不同的值",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ValueSlot(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (value != null) FontWeight.Medium else FontWeight.Normal,
            color = if (value != null) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun VerdictChip(verdict: FieldVerdict) {
    val label = when (verdict) {
        FieldVerdict.SPOOFED -> "已生效"
        FieldVerdict.MISMATCH -> "未生效"
        FieldVerdict.UNOBSERVABLE -> "读不到"
        FieldVerdict.PASSTHROUGH -> "透传"
    }
    val color = verdictColor(verdict)
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun CountPill(label: String, count: Int, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("$label $count", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun verdictColor(v: FieldVerdict): Color = when (v) {
    FieldVerdict.SPOOFED -> MaterialTheme.colorScheme.primary
    FieldVerdict.MISMATCH -> MaterialTheme.colorScheme.error
    FieldVerdict.UNOBSERVABLE -> MaterialTheme.colorScheme.tertiary
    FieldVerdict.PASSTHROUGH -> MaterialTheme.colorScheme.outline
}

private fun modeLabel(mode: String): String = when (mode) {
    SpoofSettings.MODE_ALWAYS_ON -> "始终开启"
    SpoofSettings.MODE_TIME_BASED -> "按时段"
    SpoofSettings.MODE_OFF -> "关闭"
    else -> mode
}
