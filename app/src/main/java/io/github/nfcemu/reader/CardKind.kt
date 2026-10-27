package io.github.nfcemu.reader

/**
 * 卡片类型判定 + 该类型的"能不能模拟"结论。
 *
 * 这个枚举是 M1 阶段最有价值的东西：用户贴一张卡上来，App 必须立刻告诉他
 * 「这卡属于哪类、能不能模拟、为什么」。否则后面所有功能都建立在错误预期上。
 */
enum class CardKind(
    val label: String,
    /** 一句话结论：这张卡在模拟这件事上有没有戏。 */
    val emulation: String,
) {
    MIFARE_CLASSIC(
        label = "MIFARE Classic（经典门禁卡）",
        emulation = "UID 可改（需 root 写 NFCID1）；**扇区数据无法通过 HCE 模拟** —— " +
            "它靠 Crypto1 三次认证 + 流密码，而 HCE 只能收发 APDU，两者不在同一层。",
    ),
    MIFARE_ULTRALIGHT(
        label = "MIFARE Ultralight / NTAG",
        emulation = "UID 可改。数据层是明文 14443-3 命令，理论上 HCE 无法覆盖，" +
            "实际只能用真卡中继。",
    ),
    ISO_DEP_CPU(
        label = "ISO-DEP / ISO14443-4（CPU 卡、银行卡、加密门禁）",
        emulation = "只能做 **HCE 中继**：真卡与手机同时贴在同一台读卡器上，手机转发 APDU。" +
            "凭空模拟需要卡片密钥，不可能。",
    ),
    NFC_B(
        label = "ISO14443-3B",
        emulation = "UID 可改（NXP 平台）。数据层要看是不是 ISO-DEP，是的话同 CPU 卡。",
    ),
    FELICA(
        label = "FeliCa",
        emulation = "本项目不支持。",
    ),
    ISO15693(
        label = "ISO15693 / Vicinity",
        emulation = "本项目不支持（UID 参数在不同控制器上差异很大）。",
    ),
    NFC_A_ONLY(
        label = "NFC-A 未知型号",
        emulation = "只拿到 14443-3A 层信息，无法判定数据层。建议补读 SAK / ATQA 判断。",
    ),
    UNKNOWN(
        label = "未识别",
        emulation = "没有匹配到已知技术栈。",
    ),
}
