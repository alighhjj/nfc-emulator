package io.github.nfcemu.core

/**
 * NFC 控制器平台（按 NFCC 固件与 conf 文件的命名习惯区分）。
 *
 * **重要**：本项目只在 NXP 平台上实现过 UID 改写。原因是只有 NXP 的
 * `NXP_CORE_CONF` 里有公开可见、被大量第三方工具验证过的 TAG_ID(0x33) 参数。
 * 其他平台的"等效参数"要么不存在，要么 tag 号因固件版本而异，属于猜测 ——
 * 猜错会把用户的 NFC 彻底写坏，所以这里明确标成"未实现"，而不是给个能编译的假实现。
 */
enum class NfccPlatform(
    val displayName: String,
    /** 该平台上可能承载 listen 参数的 conf 键（按优先级排列）。 */
    val coreConfKeys: List<String>,
    /** 该平台是否在本项目中具备 UID 改写能力。 */
    val uidWritable: Boolean,
    val note: String,
) {
    NXP(
        displayName = "NXP PN54x / PN55x / SN100 / SN220",
        coreConfKeys = listOf(NfcConfFile.KEY_NXP_CORE_CONF, NfcConfFile.KEY_NXP_CORE_CONF_EXTN),
        uidWritable = true,
        note = "已实现：改写 NXP_CORE_CONF 中的 TAG_ID(0x33)。这是本项目唯一经过设计的路径。",
    ),
    BROADCOM(
        displayName = "Broadcom / Infineon BCM2079x",
        coreConfKeys = listOf(NfcConfFile.KEY_NFA_DM_START_UP_CFG),
        uidWritable = false,
        note = "未实现：NFA_DM_START_UP_CFG 里是否存在等效的 NFCID1 覆盖参数未经真机验证，不做猜测。",
    ),
    STMICRO(
        displayName = "STMicroelectronics ST21NFCD / ST54",
        coreConfKeys = emptyList(),
        uidWritable = false,
        note = "未实现：ST 平台的 conf 结构与其他家差异较大，且缺少可验证的 TAG_ID 参数文档。",
    ),
    UNKNOWN(
        displayName = "未识别",
        coreConfKeys = emptyList(),
        uidWritable = false,
        note = "未识别出控制器型号。请在诊断页导出 /vendor/etc 下的 NFC 文件后手工判断。",
    );

    companion object {

        /**
         * 依据 conf 文本里的键名粗判平台 —— 只用于诊断页的展示，
         * **不参与写入决策**。真正的写入路径必须由用户显式确认平台。
         */
        fun guessFromConfKeys(keys: Collection<String>): NfccPlatform = when {
            keys.any { it == NfcConfFile.KEY_NXP_CORE_CONF || it == NfcConfFile.KEY_NXP_CORE_CONF_EXTN } -> NXP
            keys.any { it == NfcConfFile.KEY_NFA_DM_START_UP_CFG } -> BROADCOM
            keys.any { it.startsWith("ST_") || it.contains("ST21") } -> STMICRO
            else -> UNKNOWN
        }
    }
}

/** 当前平台不支持 UID 改写时抛出，UI 应当据此隐藏/禁用功能而不是让用户白跑一遍。 */
class UnsupportedPlatformException(val platform: NfccPlatform) :
    UnsupportedOperationException("${platform.displayName} 不支持 UID 改写：${platform.note}")
