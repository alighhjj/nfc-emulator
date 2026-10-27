# nfc-emulator

安卓 NFC 卡模拟实验项目。**编译、签名、发行全部在 GitHub Actions 上完成**，本地不需要 Android SDK。

> 当前进度：**M0（骨架 + 兼容性诊断）**。还没有实现 UID 写入 —— 那是 M3 的事，
> 在那之前所有界面只服务于一个目的：把「这台机器到底能做什么」算清楚。

---

## 先说清楚一件事：为什么必须 root

这是整个领域最关键的技术事实，所有"免 root 改卡号"的宣传都是假的：

| 能力 | 需要 root | 原因 |
|---|---|---|
| 读取卡片 UID | 否 | reader mode 就能拿到 |
| 读取 MIFARE Classic 扇区 | 否 | 需要扇区密钥，但不需要 root |
| HCE 中继（手机扮演卡片） | 否 | Host Card Emulation 是公开 API |
| **指定卡片 UID（卡号）** | **是** | UID 是 NFC 控制器在 ISO14443-3 层产生的，**HCE 跑在 ISO-DEP 层，根本没资格决定 UID** |
| 模拟 MIFARE Classic 数据 | 技术上不可行 | Crypto1 三次认证 + 流密码，HCE 只能收发 APDU，无法实现 14443-3 的挑战响应 |

所以"改 UID"唯一可行的路径是：**写 NFC 控制器的 listen 配置**（NXP 平台的 `NXP_CORE_CONF` 参数 `0x33`），
然后重启 `NfcService` 让固件重新加载配置。这一步必然要 root。

本项目把这件事做在三条路径上，从侵入性低到高：

| 路径 | 需要分区可写 | 需要重启 | 支持改变 UID 长度 |
|---|---|---|---|
| `RUNTIME_NCI` 运行时下发配置 | 否 | 重启 NfcService | 否（等长替换） |
| `CONF_INPLACE` 就地改 conf | **是** | 重启 NfcService | 是（4↔7↔10） |
| `MAGISK_MODULE` Magisk 模块 | 否 | **重启设备** | 是 |

诊断页会直接告诉你这三条路哪条对你可用。

---

## 目录结构

```
nfc-emulator/
├── core-nfcc/                 纯 Kotlin / 纯 JVM 模块，无 Android 依赖
│   └── src/main/kotlin/io/github/nfcemu/core/
│       ├── Hex.kt             十六进制编解码（自己写，因为 BigInteger 会吞前导 0x00）
│       ├── Tlv.kt             NCI 单字节 tag/len 的 TLV 编解码
│       ├── Uid.kt             NFCID1（4/7/10 字节）+ 非标告警
│       ├── NxpCoreConfCodec.kt  定位/替换 NXP TAG_ID(0x33)
│       ├── NfcConfFile.kt     conf 文本区块定位与**逐字符**改写
│       └── NfccPlatform.kt    平台能力声明（只有 NXP 标成"已实现"）
├── app/                       Compose UI + 诊断 + HCE 中继
├── tools/nxp_conf.py          独立 Python 实现，用于交叉验证与手工排查
└── .github/workflows/build.yml  测试 / 编译 / 签名 / 发行
```

### 为什么算法要单独放一个模块

因为本机没有 Android SDK。把业务逻辑塞进 `app` 意味着**任何一行代码都无法离线验证**，
只能靠反复推代码到 CI 上试 —— 那个循环是十分钟起步。放进纯 JVM 模块之后，
`gradle :core-nfcc:test` 在只有 JDK 17 的机器上就能跑完（甚至不需要 Android SDK）。

---

## 怎么用 CI（这是本项目唯一能出 APK 的地方）

### 1. 推到 GitHub

```bash
git init
git add .
git commit -m "M0: 骨架与兼容性诊断"
git branch -M main
git remote add origin git@github.com:<你的账号>/nfc-emulator.git
git push -u origin main
```

推上去之后 `build` workflow 会自动跑两个 job：

- `test` —— 只跑 `core-nfcc` 的单元测试，**一分钟内**就能知道算法有没有写错；
- `assemble` —— 编译 debug + release（未签名）+ lint，产物在 Actions 页面可下载。

第一次大概率不会全绿。看日志、改、再推，这是本机没有 SDK 时代价最低的循环。

### 2. 配置签名密钥（可选，但发行必需）

```bash
# 生成一个长期签名密钥（有效期 10000 天 ≈ 27 年）
keytool -genkeypair -v \
  -keystore release.jks -alias nfcemu \
  -keyalg RSA -keysize 2048 -validity 10000

# 转成 base64 单行
base64 -w0 release.jks > release.b64     # macOS: base64 -i release.jks -o release.b64
```

到仓库 `Settings → Secrets and variables → Actions` 添加：

| Secret | 说明 |
|---|---|
| `KEYSTORE_B64` | `release.b64` 的全部内容 |
| `KEY_ALIAS` | 例如 `nfcemu` |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_PASSWORD` | key 口令（通常与上面相同） |

**不配置也能跑**：workflow 会临时生成一个自签名密钥，
只是产物的签名和你下次构建的不一致，`adb install -r` 升级会失败。
`release.jks` 千万别提交（`.gitignore` 已经挡了）。

### 3. 打 tag 发行

```bash
git tag v0.1.0
git push origin v0.1.0
```

触发 `release` job：

1. `gradle :app:assembleRelease -PversionName=0.1.0 -PversionCode=<run_number>`
2. `zipalign -p -f 4`（**必须在签名之前**，否则 v2/v3 的整包签名块会失效）
3. `apksigner sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled true`
4. `apksigner verify --print-certs` 自检
5. 把 `nfc-emulator-0.1.0.apk`、对应的 `.idsig`、`checksum.txt` 发到 GitHub Release

> 为什么不用 AGP 的 `signingConfig`：它给不出 v4（`.idsig`），而且要求把 keystore 口令写进
> Gradle 配置。`apksigner` 手动签能一次拿齐四个方案。

### 4. 取 APK / 看日志：两条绕开网页的通道

本项目的开发网络到 `github.com:443` 是 **502**（直连也超时），Actions 网页打不开，
artifact 下载要登录，job logs API 要 token。所以 CI 额外做了两条 git 通道：

| 通道 | 分支 | 内容 |
|---|---|---|
| 编译产物 | `artifacts` | `apk/app-debug.apk`、`apk/app-release-unsigned.apk`、`SHA256SUMS.txt` |
| 失败诊断 | `ci-logs` | 各 job 的完整 gradle 日志、JUnit XML、lint 报告 |

取 APK：

```bash
git fetch origin artifacts
git show origin/artifacts:apk/app-debug.apk > app-debug.apk
```

看最近一次失败的日志：

```bash
git fetch origin ci-logs
git show origin/ci-logs:test/ci-build.log | tail -60
```

两个分支都是 `git push -f` 覆盖式更新，不保留历史，不参与 `main` 的任何流程。
失败时还会把关键错误行（`e: `、`FAILURE:`、`Unresolved reference` 等）写成
check-run 注解，`api.github.com` 可匿名读取，比拉整份日志更快。

### 5. 关于 gradle wrapper

仓库里**没有** `gradle-wrapper.jar`（二进制产物，作者本机无法可信地生成 ——
网络访问被企业代理挡了，拿不到 Gradle 发行包）。CI 用官方
`gradle/actions/setup-gradle` 直接安装 Gradle 8.11.1，不依赖 wrapper。

如果你想在本地用 `./gradlew`：

```bash
gradle wrapper --gradle-version 8.11.1   # 需要本机有任意 Gradle + JDK 17
```

`gradle/wrapper/gradle-wrapper.properties` 已经写好，生成出来的 wrapper 版本就是 8.11.1。

---

## 本地能做什么、不能做什么

| 任务 | 本机能不能做 |
|---|---|
| `gradle :core-nfcc:test` | 能 —— 只需要 JDK 17 |
| `gradle :app:assembleDebug` | 不能 —— 需要 Android SDK |
| 解析/改写一份 conf 文件 | 能 —— `python tools/nxp_conf.py`，只需要 Python 3.8+ |

`tools/nxp_conf.py` 是 `core-nfcc` 算法的**独立 Python 实现**，两者跑的是同一组断言：

```bash
python tools/nxp_conf.py self-test                    # 13 项断言，全过才值得相信偏移计算
python tools/nxp_conf.py dump  libnfc-nxp.conf        # 列出所有配置块和其中的 UID
python tools/nxp_conf.py patch libnfc-nxp.conf 42:4D:44:4A   # 打印 diff，不写文件
```

把手机里 `/vendor/etc/libnfc-nxp.conf` 拉下来先跑一遍 `dump`，
比直接让 App 去改要安全得多 —— 至少你知道它长什么样。

---

## 里程碑

| | 内容 | 状态 |
|---|---|---|
| M0 | 骨架、构建链、CI、兼容性诊断 | **已完成，CI 全绿（69 个单测通过，debug APK 可出）** |
| M1 | 读卡：reader mode + 各 tech 的 UID/ATQA/SAK 采集 | 待做 |
| M2 | MIFARE Classic 扇区 dump（含 Crypto1 认证）、卡片库存储 | 待做 |
| M3 | **UID 注入**（`RUNTIME_NCI` / `CONF_INPLACE`）+ 回滚状态机 | 待做 |
| M4 | Magisk 模块生成器（含 overlay 与卸载清理） | 待做 |
| M5 | 兼容性矩阵（按机型记录成功/失败样本） | 待做 |
| M6 | HCE 中继：真卡 ↔ 手机 的 APDU 转发 | 待做 |
| M7 | 屏幕关闭时可用（Xposed 仅在明确告知风险后提供） | 待做 |
| M8 | 发行打磨：R8、图标、i18n、错误文案 | 待做 |

**M3 是整个项目的成败点。** 在 M3 之前不要投入 UI 打磨 —— 如果 UID 改不了，
再漂亮的界面也只是个读卡器。

---

## 与参考实现的差异

本项目在动手前完整分析过两个同名的开源实现，下面几条是刻意避开的问题：

| 问题 | 参考实现的做法 | 本项目的做法 |
|---|---|---|
| HCE 三处标识符 | `android.nfc.action.HOST_APDU_SERVICE` / `apdu.host_service` / `<aid>` —— 全错，系统永远不会绑定服务 | 正确的 `android.nfc.cardemulation.action.HOST_APDU_SERVICE` / `host_apdu_service` / `<aid-group><aid-filter>` |
| tech filter 语义 | 8 个 `<tech>` 塞进一个 `<tech-list>`（AND 关系），物理上不可能匹配 | 每个 tech 一个 `<tech-list>`（OR 关系） |
| 定位 TAG_ID | 正则 `33:0[4,7A]:` 全文匹配，会误伤其他配置块 | 先定位 `KEY={…}` 区块，解码成字节再定位，最后**逐字符**回写 |
| 写 UID 时改长度 | 直接改，不修 length 字段 → conf 自相矛盾 | 长度变化时同步改写 `length` 字节，改完立刻回读校验 |
| 重启 NfcService | `killall com.android.nfc`（Android 10+ 无效） | `svc nfc disable/enable`，失败再回退到 `am force-stop` + 等待系统重启服务 |
| 失败反馈 | 静默 `catch`，用户只看到"没用" | 每步都有 `EmulationState.Failed(step, message, detail)`，把命令和输出一起显示 |
| 前置检查 | `mount -o rw,remount /`（system-as-root 机型必然失败） | 先算分区挂载状态，只读就改走 Magisk 模块 |

---

## 已知限制与未验证项（诚实清单）

- **只实现了 NXP 平台**。Broadcom / ST 的等效参数没有可靠文档，`NfccPlatform` 里明确标成
  `uidWritable = false` —— 不做猜测，猜错会把用户的 NFC 写坏。
- **没有真机验证**。`NXP_CORE_CONF` 的确切字节布局（`20, 02, 2D` 这种非 TLV 前缀）是从多个
  OEM 固件归纳出来的，代码用「严格 TLV 链 → 裸扫描回退」两级策略兜住了两种可能，
  但仍需在真机上确认定位偏移正确。诊断页会把定位结果显示出来。
- **HCE 中继只是骨架**。`RelayHostApduService` 现在只回 `6D00`/`9000`，用于验证通道打通。
- **未做 R8**。`isMinifyEnabled = false`，等第一次 CI 变绿、拿到真机日志后再开。
- **lint 不阻断构建**（`abortOnError = false`）。理由同上：先看报告再收紧。

---

## License

尚未确定。参考的两个上游项目分别是无 License 与 MIT，本项目代码为独立实现。
