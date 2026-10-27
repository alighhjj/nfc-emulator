package io.github.nfcemu.diagnose

import io.github.nfcemu.exec.ConfFileInfo

/**
 * 设备兼容性结论。
 *
 * 名字里的 "可行" 都是**保守判断**：只有在"拿到 root 且目标分区可写"时才敢说
 * [UID_INPLACE]；其余情况一律指向 Magisk 模块这条路，因为它是唯一不依赖分区可写性的方案。
 */
enum class Verdict(val title: String, val detail: String) {
    NO_NFC(
        "本机没有 NFC 硬件",
        "读卡、模拟、中继全部不可用。可能是设备阉割了 NFC，或系统属性里被禁用。",
    ),
    NO_ROOT(
        "无 root：只能读卡，不能改 UID",
        "读卡（reader mode）与 HCE 不需要 root；但 UID 由 NFC 控制器产生，App 无法指定，" +
            "改 UID 必须写 NFC 控制器的 listen 配置，而那需要 root。",
    ),
    UID_INPLACE(
        "可直接改写 UID",
        "已 root，且找到位于可写分区的 NFC 配置文件。可以直接就地改写并重启 NfcService。",
    ),
    NEEDS_MAGISK(
        "需要走 Magisk 模块",
        "已 root，但 NFC 配置所在分区是只读（/vendor 或 /system 的 ro 挂载）。" +
            "只能生成 Magisk 模块，由 Magisk 在开机时以 overlay 覆盖原文件。",
    ),
    NO_CONFIG(
        "找不到 NFC 配置文件",
        "已 root，但候选路径下都没找到 libnfc-nxp.conf。可能是非 NXP 平台，" +
            "或该 OEM 用了非常规路径 —— 请在诊断页导出 mount 信息后手工定位。",
    ),
}

/** 一项能力是否可用，以及为什么。 */
data class Capability(val name: String, val available: Boolean, val reason: String)

data class ReportRow(val label: String, val value: String)

data class ReportSection(val title: String, val rows: List<ReportRow>)

data class DeviceReport(
    val generatedAt: Long,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val fingerprint: String,

    val hasNfcFeature: Boolean,
    val hasHceFeature: Boolean,
    val nfcEnabled: Boolean,

    val rootAvailable: Boolean,
    val rootBackend: String?,
    val selinuxMode: String?,
    val rootIdentity: String?,

    val confFiles: List<ConfFileInfo>,

    val verdict: Verdict,
    val capabilities: List<Capability>,
    val notes: List<String>,
) {

    /** 至少有一份配置存在 —— 用于区分"平台不是 NXP"与"路径没猜对"。 */
    val anyConfFound: Boolean get() = confFiles.any { it.exists }

    fun sections(): List<ReportSection> {
        val out = ArrayList<ReportSection>(4)

        out.add(
            ReportSection(
                title = "设备",
                rows = listOf(
                    ReportRow("厂商/品牌", "$manufacturer / $brand"),
                    ReportRow("机型代号", "$model ($device)"),
                    ReportRow("Android", "$androidRelease (API $sdkInt)"),
                    ReportRow("指纹", fingerprint),
                ),
            )
        )

        out.add(
            ReportSection(
                title = "NFC 硬件与特性",
                rows = listOf(
                    ReportRow("NFC 硬件", if (hasNfcFeature) "有" else "无"),
                    ReportRow("NFC 开关", when {
                        !hasNfcFeature -> "—"
                        nfcEnabled -> "已打开"
                        else -> "已关闭（请到系统设置里打开）"
                    }),
                    ReportRow("HCE（主机卡模拟）", if (hasHceFeature) "支持" else "不支持或未声明"),
                ),
            )
        )

        out.add(
            ReportSection(
                title = "Root 状态",
                rows = listOf(
                    ReportRow("是否拿到 uid=0", if (rootAvailable) "是" else "否"),
                    ReportRow("Root 方案", rootBackend ?: "—"),
                    ReportRow("SELinux", selinuxMode ?: "—"),
                    ReportRow("身份", rootIdentity ?: "—"),
                ),
            )
        )

        out.add(
            ReportSection(
                title = "NFC 配置文件",
                rows = confFiles.map { info ->
                    ReportRow(
                        label = info.path,
                        value = when {
                            !info.exists -> "不存在"
                            else -> buildString {
                                append("${info.sizeBytes} B")
                                info.mountReadOnly?.let { append(if (it) " · 所在分区只读" else " · 所在分区可写") }
                                if (info.currentUid != null) append(" · UID ${info.currentUid}")
                                if (info.keys.isNotEmpty()) append(" · ${info.keys.size} 个配置块")
                            }
                        },
                    )
                },
            )
        )

        out.add(
            ReportSection(
                title = "能力",
                rows = capabilities.map { ReportRow(it.name, (if (it.available) "✔ " else "✘ ") + it.reason) },
            )
        )

        return out
    }
}
