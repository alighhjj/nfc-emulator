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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import io.github.nfcemu.reader.NfcReader
import io.github.nfcemu.reader.TagSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var reader: NfcReader

    /** 最近读到的卡（按 UID 去重，最多 [MAX_CARDS] 张）。 */
    private val cards = mutableStateListOf<TagSnapshot>()

    private var readerOn by mutableStateOf(true)
    private var readerStatus by mutableStateOf("")
    private var readerMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reader = NfcReader(this)
        // 回调在 binder 线程，改 Compose state 必须回主线程。
        reader.onSnapshot = { snap ->
            runOnUiThread {
                cards.removeAll { it.uidHex == snap.uidHex }
                cards.add(0, snap)
                while (cards.size > MAX_CARDS) cards.removeAt(cards.size - 1)
                readerMessage = null
                updateReaderStatus()
            }
        }
        reader.onError = { msg ->
            runOnUiThread { readerMessage = msg }
        }

        setContent {
            NfcEmuTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DiagnosticsScreen(
                        readerStatus = readerStatus,
                        readerEnabled = readerOn,
                        readerMessage = readerMessage,
                        cards = cards,
                        onToggleReader = { want ->
                            readerOn = want
                            if (want) startReader() else stopReader()
                            updateReaderStatus()
                        },
                        onOpenNfcSettings = { reader.openNfcSettings() },
                        onClearCards = { cards.clear() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来时 NFC 状态可能已经变了，这里总是重算一次。
        if (readerOn) startReader() else stopReader()
        updateReaderStatus()
    }

    override fun onPause() {
        // 必须关掉：reader mode 是全局独占的，不退会让手机后面所有 NFC 操作都失败。
        stopReader()
        super.onPause()
    }

    private fun startReader() {
        val ok = reader.start()
        if (ok) {
            // 上一次失败留下的红字在成功之后必须清掉，否则用户会以为还在坏。
            if (readerMessage != null) readerMessage = null
            return
        }
        readerMessage = when {
            !reader.hardwareAvailable -> "本机没有 NFC 硬件。"
            !reader.enabled -> "系统 NFC 未开启，先打开 NFC 再回来。"
            else -> "进入读卡模式失败（可能 NFC 正在初始化），把上面的开关关掉再打开试一次。"
        }
    }

    private fun stopReader() {
        reader.stop()
    }

    private fun updateReaderStatus() {
        readerStatus = when {
            !reader.hardwareAvailable -> "无 NFC 硬件"
            !reader.enabled -> "系统 NFC 已关闭"
            reader.running -> "读卡中 · 把卡贴到手机背面"
            readerOn -> "读卡未就绪"
            else -> "读卡已关闭（此时本机其他 NFC 功能可正常使用）"
        }
    }

    private companion object {
        const val MAX_CARDS = 5
    }
}

@Composable
private fun NfcEmuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

/**
 * 主界面。
 *
 * M0 时这里只有兼容性诊断；M1 加了读卡。刻意**没有**做"模拟开关"——
 * 那是 M3（UID 注入）的事，在 UID 能不能写成功有答案之前放个开关只会误导用户。
 */
@Composable
private fun DiagnosticsScreen(
    readerStatus: String,
    readerEnabled: Boolean,
    readerMessage: String?,
    cards: List<TagSnapshot>,
    onToggleReader: (Boolean) -> Unit,
    onOpenNfcSettings: () -> Unit,
    onClearCards: () -> Unit,
) {
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
            text = "M1 · 先认卡，再谈模拟。贴一张卡上来，看它属于哪一类。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ReaderCard(
            status = readerStatus,
            enabled = readerEnabled,
            message = readerMessage,
            cards = cards,
            onToggle = onToggleReader,
            onOpenSettings = onOpenNfcSettings,
            onClear = onClearCards,
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

// =====================================================================================
// M1 读卡区
// =====================================================================================

@Composable
private fun ReaderCard(
    status: String,
    enabled: Boolean,
    message: String?,
    cards: List<TagSnapshot>,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("读卡", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            Text(
                text = "读卡开着的时候，手机的 NFC 支付/自带门禁会被本 App 抢占 —— " +
                    "这是 reader mode 的独占特性，不是 bug。不用时请关掉。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenSettings) { Text("系统 NFC 设置") }
                if (cards.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("清空列表") }
                }
            }

            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (cards.isEmpty()) {
                Text(
                    text = "把卡贴到手机背面 NFC 感应区（通常在摄像头附近），最多保留最近 5 张。\n" +
                        "本步骤只读取公开信息（UID/ATQA/SAK/历史字节），不发送任何命令，" +
                        "不会触发认证，也不会改动卡上数据。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    cards.forEach { CardReport(it) }
}

@Composable
private fun CardReport(snap: TagSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "读到的卡",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = snap.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            snap.rows().forEach { (k, v) ->
                Column {
                    Text(
                        text = k,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = v,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // 结论卡：这张卡在"模拟"这件事上到底有没有戏，是最该被看到的文字。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "模拟可行性",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    // CardKind.emulation 里用了 ** 做强调，Compose 的 Text 不解析 Markdown，
                    // 直接显示会露出星号，这里去掉。
                    Text(
                        text = snap.kind.emulation.replace("**", ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (snap.notes.isNotEmpty()) {
                Text("备注", style = MaterialTheme.typography.labelSmall)
                snap.notes.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// =====================================================================================
// M0 诊断区
// =====================================================================================

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
