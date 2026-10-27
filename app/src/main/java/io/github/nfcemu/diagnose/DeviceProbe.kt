package io.github.nfcemu.diagnose

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcManager
import android.os.Build
import io.github.nfcemu.exec.ConfFileInfo
import io.github.nfcemu.exec.NfcConfigProbe
import io.github.nfcemu.exec.RootShell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 采集一份完整的兼容性报告。
 *
 * **这是 M0 阶段唯一真正的"功能"**：在动手改任何配置之前，先让用户在屏幕上看到
 * 「你的机器能做什么、不能做什么、为什么」。参考实现里最典型的问题是
 * 一上来就 `mount -o rw,remount /` —— 在 system-as-root 的机型上必然失败，
 * 而且失败信息根本不会显示给用户。
 */
object DeviceProbe {

    private data class RootInfo(
        val available: Boolean,
        val backend: String?,
        val selinux: String?,
        val identity: String?,
    )

    fun collect(context: Context): DeviceReport {
        val pm = context.packageManager
        val hasNfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC)
        val hasHce = pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

        val adapter = try {
            (context.getSystemService(NfcManager::class.java))?.defaultAdapter
        } catch (e: Exception) {
            null
        }
        val nfcEnabled = try {
            adapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }

        val root = probeRoot()
        val confFiles = if (hasNfc || root.available) {
            NfcConfigProbe.probeAll()
        } else {
            emptyList()
        }

        val writable = confFiles.firstOrNull { it.exists && it.mountReadOnly == false }
        val readOnly = confFiles.firstOrNull { it.exists && it.mountReadOnly == true }

        val verdict = when {
            !hasNfc -> Verdict.NO_NFC
            !root.available -> Verdict.NO_ROOT
            writable != null -> Verdict.UID_INPLACE
            readOnly != null -> Verdict.NEEDS_MAGISK
            else -> Verdict.NO_CONFIG
        }

        val capabilities = listOf(
            Capability(
                name = "读取卡片 UID（只读）",
                available = hasNfc,
                reason = if (hasNfc) "NFC reader mode，无需 root" else "无 NFC 硬件",
            ),
            Capability(
                name = "读取 MIFARE Classic 扇区",
                available = hasNfc,
                reason = if (hasNfc) "需要知道扇区密钥（默认密钥或已知密钥）" else "无 NFC 硬件",
            ),
            Capability(
                name = "HCE 中继（手机当读卡器替身）",
                available = hasHce,
                reason = if (hasHce) "系统声明支持 host card emulation" else "系统未声明 HCE 特性",
            ),
            Capability(
                name = "改写 UID（root + 可写分区）",
                available = writable != null,
                reason = writable?.let { "目标：${it.path}" }
                    ?: "没有找到位于可写分区的 NFC 配置文件",
            ),
            Capability(
                name = "改写 UID（Magisk 模块）",
                available = root.available && confFiles.any { it.exists },
                reason = when {
                    !root.available -> "需要 root"
                    !confFiles.any { it.exists } -> "没有可参考的原配置文件"
                    else -> "会把整份 conf 覆盖到 /data/adb/modules 下，开机动效"
                },
            ),
        )

        return DeviceReport(
            generatedAt = System.currentTimeMillis(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT.orEmpty(),

            hasNfcFeature = hasNfc,
            hasHceFeature = hasHce,
            nfcEnabled = nfcEnabled,

            rootAvailable = root.available,
            rootBackend = root.backend,
            selinuxMode = root.selinux,
            rootIdentity = root.identity,

            confFiles = confFiles,
            verdict = verdict,
            capabilities = capabilities,
            notes = buildNotes(hasNfc, hasHce, nfcEnabled, root, confFiles),
        )
    }

    private fun buildNotes(
        hasNfc: Boolean,
        hasHce: Boolean,
        nfcEnabled: Boolean,
        root: RootInfo,
        confFiles: List<ConfFileInfo>,
    ): List<String> {
        val notes = ArrayList<String>(4)
        if (hasNfc && !nfcEnabled) {
            notes.add("NFC 开关是关的。系统在 NFC 关闭时不会绑定 HCE 服务，所有模拟都会失败。")
        }
        if (hasNfc && !hasHce) {
            notes.add("系统没有声明 android.hardware.nfc.hce。部分定制 ROM 会移除 HCE，此时只能读卡。")
        }
        if (root.available && root.selinux != null && !root.selinux.equals("Permissive", true) &&
            !root.selinux.equals("Enforcing", true)
        ) {
            notes.add("getenforce 输出异常：${root.selinux}")
        }
        val exts = confFiles.filter { it.exists && it.keys.any { k -> k.endsWith("_EXTN") } }
        if (exts.isNotEmpty()) {
            notes.add("检测到 *_EXTN 配置块。部分机型把 listen 参数放在扩展块里，M3 需要一并检查。")
        }
        return notes
    }

    // ------------------------------------------------------------ root 探测

    /**
     * 一次 su 调用拿全部信息。
     *
     * 分成多次 run 会让 Magisk 的授权弹窗出现多次，用户体验很差，
     * 而且在"未授权"状态下每次都要等超时。
     */
    private fun probeRoot(): RootInfo {
        if (!RootShell.isRootAvailable()) {
            return RootInfo(available = false, backend = null, selinux = null, identity = null)
        }

        val script = buildString {
            append("echo @@ID\n").append("id\n")
            append("echo @@SELINUX\n").append("getenforce 2>/dev/null\n")
            append("echo @@MAGISK\n").append("magisk -V 2>/dev/null\n")
            append("echo @@KSU\n")
            append("if [ -e /data/adb/ksud ] || [ -e /data/adb/ksu ]; then echo yes; fi\n")
            append("echo @@APATCH\n")
            append("if [ -e /data/adb/ap ]; then echo yes; fi\n")
            append("echo @@END\n")
        }

        val r = RootShell.run(script, 20_000)
        val sections = LinkedHashMap<String, StringBuilder>()
        var current: String? = null
        for (line in r.stdout.split('\n')) {
            if (line.startsWith("@@")) {
                current = line.removePrefix("@@").trim()
                sections.getOrPut(current!!) { StringBuilder() }
            } else {
                current?.let { sections.getOrPut(it) { StringBuilder() }.append(line).append('\n') }
            }
        }

        fun section(name: String): String? =
            sections[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val backend = when {
            section("KSU") != null -> "KernelSU"
            section("APATCH") != null -> "APatch"
            section("MAGISK") != null -> "Magisk " + section("MAGISK")
            else -> "其他 / 未知"
        }

        return RootInfo(
            available = true,
            backend = backend,
            selinux = section("SELINUX"),
            identity = section("ID"),
        )
    }

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
}
