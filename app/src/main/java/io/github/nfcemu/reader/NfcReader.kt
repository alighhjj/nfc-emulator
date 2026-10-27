package io.github.nfcemu.reader

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import io.github.nfcemu.core.Hex
import io.github.nfcemu.core.Uid

/**
 * M1：读卡。
 *
 * 用 `enableReaderMode` 而不是 foreground dispatch / intent-filter：
 *  - reader mode 只把卡交给本 App，不走系统的"标签已发现"分发链，
 *    不会先弹出系统界面让用户选应用，能立刻拿到 [Tag]；
 *  - 关掉系统提示音、跳过 NDEF 预读，避免系统在我们的回调之前先对卡做一次读操作
 *    （某些 CPU 卡被系统预读后状态会变，后面再连就失败）；
 *  - 可以指定 presence check 间隔，把"卡还在不在"的探测频率调低，
 *    默认 125 ms 对弱场卡很容易误判离场。
 *
 * 这里**不做任何 transceive**。原因：
 *  - M1 的目标是"认卡"，认卡所需的 ATQA/SAK/历史字节/DSFID 全在 tag extras 里，不需要连接；
 *  - 不连接就不会触发 Crypto1 认证，也就不会把一张门禁卡"读坏"
 *    （部分系统在 MIFARE Classic 认证失败若干次后会锁卡）；
 *  - 不连接就不需要持有 [Tag] 引用，从根上避免 IllegalStateException。
 * 真正需要发命令的 M2（扇区 dump）会单独开一层，明确标注"会做功"。
 */
class NfcReader(private val activity: Activity) {

    /** `getDefaultAdapter` 在 API 33 起被标废弃，但新接口 `NfcAdapter.getDefaultAdapter(Context)`
     *  的替代品是 `NfcManager`；两者在这个用途上没有行为差异，暂不动。 */
    @Suppress("DEPRECATION")
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /**
     * 回调发生在 binder 线程，不是主线程 —— UI 侧必须自己切回主线程再改 state。
     */
    var onSnapshot: ((TagSnapshot) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile
    var running: Boolean = false
        private set

    val hardwareAvailable: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    /** 抑制同一张卡在 presence check 抖动时的重复回调。 */
    @Volatile
    private var lastUidHex: String? = null

    @Volatile
    private var lastSeenAt: Long = 0L

    private val callback = object : NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag?) {
            if (tag == null) return
            val snap = try {
                build(tag)
            } catch (e: RuntimeException) {
                Log.w(TAG, "读卡失败", e)
                onError?.invoke("读卡失败：${e.javaClass.simpleName}: ${e.message}")
                return
            }
            val now = System.currentTimeMillis()
            if (snap.uidHex == lastUidHex && now - lastSeenAt < REDUPE_MS) {
                lastSeenAt = now
                return
            }
            lastUidHex = snap.uidHex
            lastSeenAt = now
            onSnapshot?.invoke(snap)
        }
    }

    /**
     * 打开 reader mode。必须在 Activity resume 之后调用。
     * 重复调用是安全的（系统会先关再开）。
     *
     * @return true = 已进入 reader mode；false = 没有 NFC 硬件 / NFC 未开 / 被 ROM 拒绝。
     */
    fun start(): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        return try {
            a.enableReaderMode(activity, callback, READER_FLAGS, readerOptions())
            running = true
            true
        } catch (e: RuntimeException) {
            // 部分 OEM 在 NFC 正在初始化时抛 IllegalStateException。
            Log.w(TAG, "enableReaderMode 失败", e)
            running = false
            false
        }
    }

    fun stop() {
        val a = adapter ?: return
        if (!running) return
        try {
            a.disableReaderMode(activity)
        } catch (e: RuntimeException) {
            Log.w(TAG, "disableReaderMode 失败", e)
        } finally {
            running = false
        }
    }

    /** 跳到系统 NFC 设置页。各 OEM 的 NFC 开关位置不统一，只能给一个入口。 */
    fun openNfcSettings() {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: RuntimeException) {
            onError?.invoke("打不开 NFC 设置：${e.message}")
        }
    }

    private fun readerOptions(): Bundle = Bundle().apply {
        // 默认 125 ms 太敏感：手持晃动、卡离 1~2 cm 都会触发一次"离场"回调。
        putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS)
    }

    // =================================================================================
    // Tag → TagSnapshot。全部走 tag extras（发现阶段缓存的值），不做 transceive。
    // =================================================================================

    private fun build(tag: Tag): TagSnapshot {
        val techs = tag.techList.map { it.substringAfterLast('.') }.sorted()

        val nfcA = NfcA.get(tag)
        val nfcB = NfcB.get(tag)
        val nfcF = NfcF.get(tag)
        val nfcV = NfcV.get(tag)
        val isoDep = IsoDep.get(tag)
        val mfc = MifareClassic.get(tag)
        val mfu = MifareUltralight.get(tag)

        val atqaBytes: ByteArray? = try {
            nfcA?.atqa
        } catch (e: RuntimeException) {
            null
        }
        val sakInt: Int? = try {
            // NfcA.getSak() 是原始 short，实际只有低 8 位有效。
            nfcA?.sak?.toInt()?.and(0xFF)
        } catch (e: RuntimeException) {
            null
        }
        val atqaInt: Int? = if (atqaBytes != null && atqaBytes.size >= 2) {
            ((atqaBytes[0].toInt() and 0xFF) shl 8) or (atqaBytes[1].toInt() and 0xFF)
        } else {
            null
        }

        val profile = TechProfile(
            hasNfcA = nfcA != null,
            hasNfcB = nfcB != null,
            hasNfcF = nfcF != null,
            hasNfcV = nfcV != null,
            hasIsoDep = isoDep != null,
            hasMifareClassic = mfc != null,
            hasMifareUltralight = mfu != null,
            hasNdef = techs.contains("Ndef") || techs.contains("NdefFormatable"),
            sak = sakInt,
            atqa = atqaInt,
        )

        val verdict = TagClassifier.classify(profile)
        val notes = ArrayList<String>(verdict.notes)

        val uidBytes = tag.id
        if (uidBytes.size in Uid.SUPPORTED_SIZES) {
            try {
                notes.addAll(Uid.of(uidBytes).warnings())
            } catch (e: RuntimeException) {
                // warnings() 只是附加信息，失败不影响读卡
            }
        } else {
            notes.add("UID 长度 ${uidBytes.size} 字节，不在 4/7/10 之列，无法作为 NFCID1 注入。")
        }

        val mfcType = intOf { mfc?.type }
        val mfcSize = intOf { mfc?.size }
        val mfcSectors = intOf { mfc?.sectorCount }
        val mfcBlocks = intOf { mfc?.blockCount }

        val mfcTypeName = when (mfcType) {
            MifareClassic.TYPE_CLASSIC -> "Classic"
            MifareClassic.TYPE_PLUS -> "Plus"
            MifareClassic.TYPE_PRO -> "Pro"
            null -> null
            else -> "未知($mfcType)"
        }

        if (mfc != null && mfcTypeName != null) {
            notes.add(
                "MIFARE $mfcTypeName：${mfcSectors ?: "?"} 个扇区 / ${mfcBlocks ?: "?"} 个块 / " +
                    "${mfcSize ?: "?"} 字节。数据层需要 Crypto1 认证，HCE 无法模拟 —— " +
                    "换 UID 之后数据得靠真卡中继。"
            )
        }
        if (isoDep != null && mfc != null) {
            notes.add("它的 ISO-DEP 侧可以收发 APDU，优先按 APDU 路径做中继，比解 Crypto1 现实得多。")
        }

        return TagSnapshot(
            uidHex = Hex.encode(uidBytes, separator = " "),
            uidBytes = uidBytes.size,
            techs = techs,
            kind = verdict.kind,
            seenAt = System.currentTimeMillis(),
            atqa = atqaBytes?.let { Hex.encode(it, separator = " ") },
            sak = sakInt?.let { formatSak(it) },
            mifareType = mfcTypeName,
            mifareSize = mfcSize?.let { "$it 字节" },
            mifareSectors = if (mfcSectors != null && mfcBlocks != null) {
                "$mfcSectors 扇区 / $mfcBlocks 块"
            } else {
                null
            },
            isoDepHistorical = bytesOf { isoDep?.hiLayerResponse }
                ?.let { Hex.encode(it, separator = " ") },
            isoDepExtended = booleanOf { isoDep?.isExtendedLengthApduSupported },
            nfcBAppData = bytesOf { nfcB?.applicationData }?.let { Hex.encode(it, separator = " ") },
            nfcBProtocol = bytesOf { nfcB?.protocolInfo }?.let { Hex.encode(it, separator = " ") },
            nfcFSystemCode = bytesOf { nfcF?.systemCode }?.let { Hex.encode(it, separator = " ") },
            nfcFManufacturer = bytesOf { nfcF?.manufacturer }?.let { Hex.encode(it, separator = " ") },
            nfcVDsfId = intOf { nfcV?.dsfId?.toInt()?.and(0xFF) }
                ?.let { "0x" + it.toString(16).uppercase().padStart(2, '0') },
            maxTransceive = firstMaxTransceive(isoDep, nfcA, nfcB, nfcF, nfcV, mfc),
            notes = notes,
        )
    }

    private fun formatSak(sak: Int): String = buildString {
        append("0x").append(sak.toString(16).uppercase().padStart(2, '0'))
        TagClassifier.sakModelName(sak)?.let { append(" → ").append(it) }
        val bits = TagClassifier.sakBits(sak)
        if (bits.isNotEmpty()) {
            append('（').append(bits.joinToString("；")).append('）')
        }
    }

    /**
     * 各 tech 的 maxTransceiveLength。
     *
     * `BasicTagTechnology.getMaxTransceiveLength()` 在部分 ROM 上会抛
     * IllegalStateException（内部要先取到 NfcService 引用），所以逐个吞掉。
     */
    private fun firstMaxTransceive(vararg techs: Any?): Int? {
        for (t in techs) {
            val v: Int? = when {
                t is IsoDep -> intOf { t.maxTransceiveLength }
                t is NfcA -> intOf { t.maxTransceiveLength }
                t is NfcB -> intOf { t.maxTransceiveLength }
                t is NfcF -> intOf { t.maxTransceiveLength }
                t is NfcV -> intOf { t.maxTransceiveLength }
                t is MifareClassic -> intOf { t.maxTransceiveLength }
                else -> null
            }
            if (v != null && v > 0) return v
        }
        return null
    }

    /** 读取可能抛异常的 int 类属性，失败返回 null。 */
    private inline fun intOf(block: () -> Int?): Int? = try {
        block()
    } catch (e: RuntimeException) {
        Log.d(TAG, "属性读取失败: ${e.message}")
        null
    }

    private inline fun booleanOf(block: () -> Boolean?): Boolean? = try {
        block()
    } catch (e: RuntimeException) {
        null
    }

    private inline fun bytesOf(block: () -> ByteArray?): ByteArray? = try {
        block()
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        private const val TAG = "NfcReader"
        private const val REDUPE_MS = 500L
        private const val PRESENCE_CHECK_DELAY_MS = 500

        /**
         * 刻意**不含** `FLAG_READER_NFC_BARCODE`：
         * 条码模式会让部分设备的 NFC 控制器进入持续场，耗电且干扰真实卡，
         * 而本项目根本不关心条码。
         *
         * 用 `val` 而不是 `const val`：这几个常量来自 android.jar，
         * 在 JVM 侧是 `static final` 而不是 Kotlin 的编译期常量，
         * `const val` 是否接受取决于编译器版本，这里不赌。
         */
        private val READER_FLAGS: Int =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
}
