package io.github.nfcemu.exec

/**
 * 一次 shell 调用的结果。
 *
 * 刻意不用 `Runtime.exec(String)`：它按空格切参数，遇到带引号/管道的命令会静默错切。
 * 这里统一走 `su -c <整条命令>`，由 sh 负责解析。
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0

    fun stdoutTrimmed(): String = stdout.trim()

    /** 出错时把 stdout 与 stderr 都塞进一行，方便直接扔进异常消息里。 */
    fun describe(): String {
        val parts = ArrayList<String>(3)
        parts.add("exit=$exitCode")
        if (stdout.isNotBlank()) parts.add("out=${stdout.trim().take(400)}")
        if (stderr.isNotBlank()) parts.add("err=${stderr.trim().take(400)}")
        return parts.joinToString(" | ")
    }
}

/**
 * root shell 封装。
 *
 * 设计取舍：
 *  - **不引入 libsu**。libsu 走 jitpack 分发，而本项目的 CI 只配置了
 *    google()/mavenCentral()；多一个第三方仓库就多一处会在 CI 上解析失败的地方。
 *    这里用 ProcessBuilder 直接起 `su -c`，功能足够且零依赖。
 *  - **每个命令都必须带超时**。Magisk 首次授权会弹窗，用户不点就会一直挂着；
 *    没有超时的实现会让整个诊断页永久卡住。
 *  - stdout / stderr 必须**并发**读取。单线程先读 stdout 再读 stderr，
 *    在输出量超过管道缓冲区（64KB）时会死锁。
 */
object RootShell {

    const val DEFAULT_TIMEOUT_MS = 15_000L

    @Volatile
    private var cachedRootState: Boolean? = null

    /** 当前进程是否真的拿到了 uid=0。带缓存，避免诊断页反复弹授权框。 */
    fun isRootAvailable(forceRefresh: Boolean = false): Boolean {
        cachedRootState?.let { if (!forceRefresh) return it }
        val r = run("id", 5_000)
        val ok = r.ok && r.stdout.contains("uid=0")
        cachedRootState = ok
        return ok
    }

    fun invalidateCache() {
        cachedRootState = null
    }

    /**
     * 以 root 执行一条 sh 命令。
     *
     * @param timeoutMs 超时后进程会被 `destroy()`，返回 exitCode = -1
     *                  （-1 不会与任何真实退出码混淆，真实退出码是 0..255）
     *
     * 注意：这里**不用** `Process.waitFor(timeout, TimeUnit)` 和 `Process.isAlive()`，
     * 它们都是 Java 8 API，在 Android 上要 API 26 才有。minSdk 是 24，
     * 直接用会在 7.x 设备上抛 NoSuchMethodError。改用看门狗线程 + 阻塞 waitFor()。
     */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        val process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: Exception) {
            return ShellResult(-1, "", "无法启动 su：${e.javaClass.simpleName}: ${e.message}")
        }

        val outBuf = StringBuffer()
        val errBuf = StringBuffer()

        // 两个流必须并发读，否则大输出会互相堵死
        val outReader = Thread {
            try {
                process.inputStream.bufferedReader().use { outBuf.append(it.readText()) }
            } catch (_: Exception) {
            }
        }
        val errReader = Thread {
            try {
                process.errorStream.bufferedReader().use { errBuf.append(it.readText()) }
            } catch (_: Exception) {
            }
        }
        outReader.isDaemon = true
        errReader.isDaemon = true
        outReader.start()
        errReader.start()

        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                timedOut.set(true)
                process.destroy()
            } catch (_: InterruptedException) {
                // 正常结束，看门狗被中断
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        try {
            process.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroy()
            watchdog.interrupt()
            return ShellResult(-1, outBuf.toString(), "被中断")
        }
        watchdog.interrupt()

        outReader.join(2_000)
        errReader.join(2_000)

        if (timedOut.get()) {
            return ShellResult(-1, outBuf.toString(), "命令超时（${timeoutMs}ms）：$command")
        }

        val code = try {
            process.exitValue()
        } catch (e: IllegalThreadStateException) {
            -1
        }
        return ShellResult(code, outBuf.toString(), errBuf.toString())
    }

    /** 需要"失败即中止"的场景用这个。 */
    fun runOrThrow(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        val r = run(command, timeoutMs)
        check(r.ok) { "命令执行失败：$command → ${r.describe()}" }
        return r.stdout
    }

    /** 单引号包裹参数，避免路径/内容里的空格与特殊字符被 sh 解释。 */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
