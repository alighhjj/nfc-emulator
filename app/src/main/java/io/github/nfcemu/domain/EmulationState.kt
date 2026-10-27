package io.github.nfcemu.domain

import io.github.nfcemu.core.Uid

/**
 * 三条 UID 注入路径。
 *
 * 这不是"三种可选实现"，而是**三个不同代偿层级的方案**，可靠性依次升高、
 * 对设备的侵入性也依次升高。UI 应当按 [order] 从低到高推荐。
 */
enum class InjectionPath(
    val order: Int,
    val label: String,
    val summary: String,
    /** 是否要求目标分区可写（否则只能走 Magisk overlay）。 */
    val requiresWritablePartition: Boolean,
    /** 是否要求重启 NfcService 生效。 */
    val requiresNfcRestart: Boolean,
    /** 是否要求重启设备才生效。 */
    val requiresReboot: Boolean,
    /** 是否支持改变 UID 字节长度（4↔7↔10）。 */
    val supportsResize: Boolean,
) {
    RUNTIME_NCI(
        order = 0,
        label = "运行时改写 NCI 配置",
        summary = "通过 su 直接给 NFCC 下发 CORE_SET_CONFIG 覆盖 TAG_ID，不落盘。",
        requiresWritablePartition = false,
        requiresNfcRestart = true,
        requiresReboot = false,
        supportsResize = false,
    ),
    CONF_INPLACE(
        order = 1,
        label = "就地改写 conf 文件",
        summary = "把 /vendor/etc 下的 libnfc-nxp.conf 改掉，再重启 NfcService。",
        requiresWritablePartition = true,
        requiresNfcRestart = true,
        requiresReboot = false,
        supportsResize = true,
    ),
    MAGISK_MODULE(
        order = 2,
        label = "Magisk 模块（最稳）",
        summary = "生成 /data/adb/modules 下的 overlay 模块，开机自动挂载，重启生效。",
        requiresWritablePartition = false,
        requiresNfcRestart = false,
        requiresReboot = true,
        supportsResize = true,
    ),
}

/** 模拟流程的状态机。刻意做成 sealed，保证 UI 的 when 分支是穷尽的。 */
sealed interface EmulationState {

    /** 什么都没做。 */
    data object Idle : EmulationState

    /** 正在执行某一步。`step` 是给人看的短句，直接显示在进度条上方。 */
    data class Working(val step: String) : EmulationState

    /** 已生效。 */
    data class Active(
        val uid: Uid,
        val path: InjectionPath,
        val appliedAt: Long,
    ) : EmulationState

    /**
     * 失败。
     *
     * 必须同时保留 [step] 与 [detail]：只给一句"失败了"等于什么都没说，
     * 这个项目的失败原因几乎全部落在"哪一步、什么命令、什么输出"上。
     */
    data class Failed(
        val step: String,
        val message: String,
        val detail: String? = null,
    ) : EmulationState

    /** 已回滚（把原配置恢复回去）。 */
    data class RolledBack(val note: String) : EmulationState
}
