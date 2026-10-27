package io.github.nfcemu.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nfcemu.diagnose.DeviceProbe
import io.github.nfcemu.diagnose.DeviceReport
import io.github.nfcemu.diagnose.ReportSection
import io.github.nfcemu.hce.RelayHostApduService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NfcEmuTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DiagnosticsScreen()
                }
            }
        }
    }
}

@Composable
private fun NfcEmuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

/**
 * M0 的唯一界面：兼容性诊断。
 *
 * 刻意不做卡片列表 / 模拟开关 / 扫描动画 —— 那些都要等 M3（UID 注入）跑通之后才有意义。
 * 在"能不能改 UID"这个核心问题有答案之前堆 UI，等于把时间花在会被推翻的东西上。
 */
@Composable
private fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<DeviceReport?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    fun refresh() {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { DeviceProbe.collect(context) }
            }
            report = result.getOrNull()
            failure = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
            busy = false
            tick++
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "NFC 卡模拟实验室",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "M0 · 兼容性诊断。先把「这台机器能做什么」算清楚，再谈模拟。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { refresh() }, enabled = !busy) {
                Text(if (busy) "检测中…" else "重新检测")
            }
            if (busy) {
                CircularProgressIndicator(Modifier.height(20.dp))
            }
        }

        failure?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("检测失败", fontWeight = FontWeight.Bold)
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        report?.let { r ->
            VerdictCard(r)

            r.sections().forEach { SectionCard(it) }

            if (r.notes.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("注意", fontWeight = FontWeight.Bold)
                        r.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        HceCard(tick)

        Text(
            text = "报告生成于 " + (report?.let { DeviceProbe.formatTime(it.generatedAt) } ?: "—"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerdictCard(report: DeviceReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "结论",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(report.verdict.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(report.verdict.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionCard(section: ReportSection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(section.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            section.rows.forEach { row ->
                Column {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun HceCard(tick: Int) {
    val alive = remember(tick) { RelayHostApduService.serviceAlive }
    val last = remember(tick) { RelayHostApduService.lastInvokedAt }
    val history = remember(tick) { RelayHostApduService.historySnapshot() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("HCE 通道", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (alive) "服务已被系统绑定 ✔" else "服务尚未被绑定（把手机贴到读卡器上试一次）",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = if (last == 0L) "从未收到 APDU" else "最后一次收到 APDU：" + DeviceProbe.formatTime(last),
                style = MaterialTheme.typography.bodySmall,
            )
            if (history.isNotEmpty()) {
                Text("最近 APDU：", style = MaterialTheme.typography.labelSmall)
                history.takeLast(10).forEach {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            } else {
                Text(
                    "提示：HCE 只有在系统 NFC 打开、且读卡器发起 SELECT AID 时才会触发。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
