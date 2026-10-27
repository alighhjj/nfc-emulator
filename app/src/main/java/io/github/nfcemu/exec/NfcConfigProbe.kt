package io.github.nfcemu.exec

import io.github.nfcemu.core.NfcConfFile
import io.github.nfcemu.core.NfccPlatform
import io.github.nfcemu.core.NxpCoreConfCodec
import java.io.File

/** 一份 NFC 配置文件的探测结果。 */
data class ConfFileInfo(
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    /** 所在分区的挂载读写状态：true = 只读。null = 没查到（可能是 system-as-root）。 */
    val mountReadOnly: Boolean?,
    /** 文件里所有大写 `KEY={…}` 的键名。 */
    val keys: List<String>,
    /** 核心配置块里当前的 NFCID1（如果有）。 */
    val currentUid: String?,
    /** 依据键名推断的平台。 */
    val platform: NfccPlatform,
    val readError: String? = null,
) {
    val writableInPlace: Boolean get() = exists && mountReadOnly == false
}

/**
 * 探测设备上 NFC 配置文件的位置与可写性。
 *
 * 这一层是整个功能的地基：如果找不到 conf，或者所在分区是 ro 挂载，
 * 那么"直接就地改写"这条路就是死的，只能走 Magisk 模块（把文件覆盖到 /data/adb/modules，
 * 由 Magisk 在开机时以 overlay 方式挂载）。诊断页必须先把这个结论算出来，
 * 不然用户点"写入"只会得到一句语焉不详的失败。
 *
 * 候选路径来自对多个 OEM 固件的归纳。注意 **`/vendor/etc/nfc/` 这个子目录**：
 * 很多早期方案只找 `/vendor/etc/libnfc-nxp.conf`，在新机型上就会误判为"不支持"。
 */
object NfcConfigProbe {

    val CANDIDATE_PATHS: List<String> = listOf(
        "/vendor/etc/libnfc-nxp.conf",
        "/vendor/etc/nfc/libnfc-nxp.conf",
        "/odm/etc/libnfc-nxp.conf",
        "/odm/etc/nfc/libnfc-nxp.conf",
        "/system/etc/libnfc-nxp.conf",
        "/system/etc/nfc/libnfc-nxp.conf",
        "/product/etc/libnfc-nxp.conf",
        "/vendor/etc/libnfc-nxp_RF.conf",
    )

    private const val MARK_BEGIN = "@@BEGIN "
    private const val MARK_MISSING = "@@MISSING"
    private const val MARK_END = "@@END"
    private const val MARK_MOUNTS = "@@MOUNTS"

    /**
     * 一次性探测全部候选路径。
     *
     * 有 root 时只起 **一个** su 进程批量 cat 全部文件 ——
     * 每个路径单独起一次 su 在 Magisk 上要 100~300ms，8 个路径就是两秒多，
     * 诊断页会明显卡顿。
     */
    fun probeAll(): List<ConfFileInfo> {
        val rooted = RootShell.isRootAvailable()
        return if (rooted) {
            val (contents, mounts) = readViaRoot()
            build(contents, mounts)
        } else {
            build(readDirect(), emptyMap())
        }
    }

    /** 读某个文件的全部文本（有 root 走 root，否则直读）。 */
    fun readOne(path: String): String? {
        return if (RootShell.isRootAvailable()) {
            val r = RootShell.run("cat ${RootShell.quote(path)}", 10_000)
            if (r.ok) r.stdout else null
        } else {
            tryReadDirect(path)
        }
    }

    // ------------------------------------------------------------ 内部

    private fun build(contents: Map<String, String?>, mounts: Map<String, String>): List<ConfFileInfo> =
        CANDIDATE_PATHS.map { path ->
            val text = contents[path]
            if (text == null) {
                ConfFileInfo(
                    path = path,
                    exists = false,
                    sizeBytes = 0,
                    mountReadOnly = mountStateOf(path, mounts),
                    keys = emptyList(),
                    currentUid = null,
                    platform = NfccPlatform.UNKNOWN,
                    readError = null,
                )
            } else {
                val keys = NfcConfFile.listKeys(text)
                val platform = NfccPlatform.guessFromConfKeys(keys)
                val uid = runCatching {
                    NfcConfFile.readUid(text, NfcConfFile.KEY_NXP_CORE_CONF)
                        ?: NfcConfFile.readUid(text, NfcConfFile.KEY_NXP_CORE_CONF_EXTN)
                }.getOrNull()

                ConfFileInfo(
                    path = path,
                    exists = true,
                    sizeBytes = text.length.toLong(),
                    mountReadOnly = mountStateOf(path, mounts),
                    keys = keys,
                    currentUid = uid?.toDisplay(),
                    platform = platform,
                    readError = null,
                )
            }
        }

    private fun readViaRoot(): Pair<Map<String, String?>, Map<String, String>> {
        val script = StringBuilder()
        for (p in CANDIDATE_PATHS) {
            script.append("echo ").append(MARK_BEGIN).append(p).append('\n')
            script.append("if [ -f ").append(RootShell.quote(p)).append(" ]; then cat ")
                .append(RootShell.quote(p)).append("; ")
                .append("else echo ").append(MARK_MISSING).append("; fi\n")
            // 单独一行 echo：文件末尾没有换行符时，保证后面的标记不会被粘在内容行上
            script.append("echo\n")
            script.append("echo ").append(MARK_END).append('\n')
        }
        script.append("echo ").append(MARK_MOUNTS).append('\n')
        script.append("mount\n")

        val r = RootShell.run(script.toString(), 30_000)
        if (!r.ok && r.stdout.isBlank()) {
            return emptyMap<String, String?>() to emptyMap<String, String>()
        }

        val contents = LinkedHashMap<String, String?>()
        val mounts = LinkedHashMap<String, String>()

        val lines = r.stdout.split('\n')
        var currentPath: String? = null
        val buffer = StringBuilder()
        var inMounts = false

        for (line in lines) {
            when {
                line.startsWith(MARK_BEGIN) -> {
                    currentPath = line.removePrefix(MARK_BEGIN).trim()
                    buffer.setLength(0)
                }

                line.trim() == MARK_END -> {
                    val p = currentPath
                    if (p != null) {
                        val body = buffer.toString()
                        contents[p] = if (body.trim() == MARK_MISSING) null else body
                    }
                    currentPath = null
                    buffer.setLength(0)
                }

                line.trim() == MARK_MOUNTS -> inMounts = true

                inMounts -> {
                    val mp = parseMountPoint(line)
                    if (mp != null) mounts[mp] = line
                }

                currentPath != null -> buffer.append(line).append('\n')
            }
        }
        return contents to mounts
    }

    private fun readDirect(): Map<String, String?> =
        CANDIDATE_PATHS.associateWith { tryReadDirect(it) }

    private fun tryReadDirect(path: String): String? = try {
        val f = File(path)
        if (f.isFile && f.canRead()) f.readText() else null
    } catch (e: Exception) {
        null
    }

    /** `mount` 一行形如 `/dev/block/dm-4 on /vendor type ext4 (ro,seclabel,relatime)`。 */
    private fun parseMountPoint(line: String): String? {
        val onIdx = line.indexOf(" on ")
        if (onIdx < 0) return null
        val rest = line.substring(onIdx + 4)
        val spaceIdx = rest.indexOf(" type ")
        if (spaceIdx < 0) return null
        return rest.substring(0, spaceIdx)
    }

    private fun mountStateOf(path: String, mounts: Map<String, String>): Boolean? {
        val partition = partitionOf(path) ?: return null
        val line = mounts[partition] ?: return null
        val parenIdx = line.lastIndexOf('(')
        if (parenIdx < 0) return null
        val flags = line.substring(parenIdx)
        return flags.contains("ro,") || flags.contains("(ro)")
    }

    /** `/vendor/etc/nfc/libnfc-nxp.conf` → `/vendor`。 */
    fun partitionOf(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return "/" + parts[0]
    }
}
