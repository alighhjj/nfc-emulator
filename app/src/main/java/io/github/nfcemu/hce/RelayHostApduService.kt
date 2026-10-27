package io.github.nfcemu.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import io.github.nfcemu.core.Hex
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HCE 中继服务的骨架。
 *
 * M0 阶段它只做两件事：
 *  1. **证明 HCE 通道是通的** —— 手机当读卡器、外部读卡器贴近时，系统会绑定这个服务
 *     并把 APDU 送进 [processCommandApdu]。只要这里被调用过，就说明 manifest 里的
 *     action / meta-data / AID 三处标识符全对。
 *  2. 把收到的 APDU 记下来，给诊断页展示。
 *
 * 真正的"把真卡的响应转发回去"（中继模式）在 M6 实现：需要同时开 reader mode 读真卡，
 * 再在这里把 APDU 转给真卡取响应。中间涉及状态机与超时，不适合放在 M0。
 *
 * 返回值约定（ISO 7816-4）：
 *  - `9000` 成功
 *  - `6A82` 文件/应用未找到（未知 AID 的标准应答）
 *  - `6D00` 指令不支持
 */
class RelayHostApduService : HostApduService() {

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
    }

    override fun onDestroy() {
        serviceAlive = false
        super.onDestroy()
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apdu = commandApdu ?: ByteArray(0)
        lastInvokedAt = System.currentTimeMillis()
        record(apdu)

        return when {
            isSelect(apdu, TEST_AID) -> STATUS_OK
            // 未知指令统一回 6D00，让读卡器立刻放弃，而不是等超时（超时会让读卡体验极差）
            else -> STATUS_INSTRUCTION_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        val name = when (reason) {
            HostApduService.DEACTIVATION_LINK_LOSS -> "LINK_LOSS"
            HostApduService.DEACTIVATION_DESELECTED -> "DESELECTED"
            else -> "UNKNOWN($reason)"
        }
        recordNote("onDeactivated: $name")
    }

    companion object {

        val STATUS_OK: ByteArray = byteArrayOf(0x90.toByte(), 0x00)
        val STATUS_FILE_NOT_FOUND: ByteArray = byteArrayOf(0x6A, 0x82.toByte())
        val STATUS_INSTRUCTION_NOT_SUPPORTED: ByteArray = byteArrayOf(0x6D, 0x00)

        /** 与 res/xml/apdu_service.xml 里的 aid-filter 必须一致。 */
        val TEST_AID: ByteArray = byteArrayOf(
            0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
        )

        private const val MAX_HISTORY = 64

        /** 服务是否被系统绑定过 —— 诊断页用它判断 HCE 注册是否成功。 */
        @Volatile
        var serviceAlive: Boolean = false
            private set

        @Volatile
        var lastInvokedAt: Long = 0L
            private set

        private val history = CopyOnWriteArrayList<String>()

        fun historySnapshot(): List<String> = history.toList()

        fun clearHistory() {
            history.clear()
            lastInvokedAt = 0L
        }

        private fun record(apdu: ByteArray) {
            val hex = if (apdu.isEmpty()) "(空)" else Hex.encode(apdu)
            push("← " + hex + "  (" + apdu.size + "B)")
        }

        private fun recordNote(note: String) = push("· $note")

        private fun push(line: String) {
            if (history.size >= MAX_HISTORY) {
                history.removeAt(0)
            }
            history.add(line)
        }

        /**
         * 判断是不是 SELECT AID。
         *
         * 同时兼容短 APDU（`00 A4 04 00 Lc <AID>`）与扩展长度 APDU
         * （`00 A4 04 00 00 Lc1 Lc2 <AID>`，第 5 字节为 0x00 表示后面还有两字节长度）。
         */
        fun isSelect(apdu: ByteArray, aid: ByteArray): Boolean {
            if (apdu.size < 5) return false
            if (apdu[0].toInt() != 0x00) return false
            if ((apdu[1].toInt() and 0xFF) != 0xA4) return false
            if ((apdu[2].toInt() and 0xFF) != 0x04) return false
            if (apdu[3].toInt() != 0x00) return false

            var lcIndex = 4
            var lc = apdu[4].toInt() and 0xFF
            if (lc == 0x00) {
                if (apdu.size < 7) return false
                lc = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
                lcIndex = 6
            }
            if (lc != aid.size) return false
            val start = lcIndex + 1
            if (start + aid.size > apdu.size) return false
            for (i in aid.indices) {
                if (apdu[start + i] != aid[i]) return false
            }
            return true
        }
    }
}
