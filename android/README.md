# Android 移植源码（Android TV 端）

本目录是「局域网文件传输工具」的 **Android 原生实现**，整体设计**仿照 [sunshine-send](https://github.com/diduweiwu/sunshine-send)**：
电视端内置 HTTP 服务器 + 二维码「扫码即传」，**手机浏览器扫码即可上传**（TV 端内置网页上传页 `tv/src/main/res/raw/index.html`，支持分块、断点续传与实时进度）。

> **手机端 App 已停用**：交付形态只保留电视端 **SFTT**。上传路径改为「手机浏览器扫码 → TV 内置网页上传页」。
> 原生手机端源码保留在 `android/phone/` 下供参考，但**不参与 Gradle 构建**（`settings.gradle.kts` 已移除 `include(":phone")`），
> 备份见 `app/data/retired-phone-module.zip`。

工程为 3 模块 Gradle 项目：`shared`（共享协议层）+ `tv`（电视端）+ `harness`（JVM 验证模块）。

---

## 一、与 sunshine-send 的对齐点 & 差异

| 维度 | sunshine-send | 本项目 |
|------|---------------|--------|
| 传输通道 | TV 端内置 HTTP 服务器，手机浏览器上传 | TV 端内置 HTTP 服务器（**主通道**）+ 原生 TCP 通道（兼容） |
| 地址获取 | 二维码扫码即传 | 二维码扫码即传 + NSD 自动发现（局域网内其它客户端） |
| 完整性 | 无（HTTP 层裸传） | **逐片 SHA-256 + 整文件 SHA-256 双重校验** |
| 并发 | 单流 multipart | 每片独立请求，默认 6 线程并发 |
| TV 端能力 | 文件列表、打开、删除、APK 自动安装、持久化、D-pad 焦点动画 | 同左（并额外支持进度条/状态色） |

> 本项目**保留**了原生 TCP 通道，因此原生客户端 ↔ Python 电视端仍可跨语言互操作。

---

## 二、模块职责

| 模块 | 内容 |
|------|------|
| `shared` | `Protocol.kt`（TCP 通道常量/消息）、`FramedSocket.kt`（带缓冲读写，关键！）、`Crypto.kt`（SHA-256）、`ChunkPlanner.kt`（分块）、`HttpProto.kt`（HTTP 通道协议常量与请求头）、`Base64Codec.kt`（纯 Kotlin Base64，供 minSdk 21 用） |
| `tv` | `http/HttpUploadServer.kt`（**内置 HTTP 服务器**，零第三方依赖）、`data/FileRepository.kt`（持久化）、`ui/FileAdapter.kt`（文件列表）、`util/QrCodeUtil.kt`、`util/PortUtil.kt`、`util/ApkInstaller.kt`、`TvServer.kt`（TCP 兼容通道）、`NsdRegistrar.kt`、`MainActivity.kt`、`res/raw/index.html`（**内嵌网页上传页**） |
| `harness` | **JVM 验证模块**：直接引用 `shared` 与 `tv/http` 的**源文件**（非复制），让真实 Kotlin HTTP 服务器跑在 JVM 上，供 Python 客户端实测 |

---

## 三、HTTP 通道协议（浏览器/客户端 ↔ 电视）

电视端监听 **9527** 端口，路由如下：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 返回内置 Web 上传页（`res/raw/index.html`） |
| GET | `/api/device` | 设备信息 |
| GET | `/api/files` | 已接收文件清单 |
| GET | `/api/upload/status?session=` | 上传会话进度 |
| POST | `/api/upload/init` | 创建会话，返回 `session` / `chunk_size` |
| POST | `/api/upload/chunk` | 上传单个分块（请求体为**裸二进制**） |
| POST | `/api/upload/commit` | 合并分块 → 整文件校验 → 落盘 |
| DELETE | `/api/file/delete?name=xx` | 删除文件 |

请求头约定：

```
X-Lan-Name          文件名（URL 编码，仅 init）
X-Lan-Total-Size    整文件字节数（仅 init）
X-Lan-File-Sha256   整文件 SHA-256（仅 init，可选）
X-Lan-Chunk-Count   总分块数（仅 init）
X-Lan-Session       会话 ID（仅 chunk）
X-Lan-Index         分块序号（仅 chunk）
X-Lan-Size          分块字节数（仅 chunk）
X-Lan-Chunk-Sha256  分块 SHA-256（仅 chunk，可选）
X-Lan-Commit-Session 会话 ID（仅 commit）
X-Lan-Resp-Sha256   响应头：整文件 SHA-256（commit 成功后）
```

分块大小 **1 MiB**（`HttpProto.HTTP_CHUNK_SIZE`），默认并发 **6** 线程。

**校验规则**：分片哈希声明了就必须匹配（不符 → 该分片 400 `chunk checksum mismatch`）；
commit 后服务端计算整文件哈希，与 init 时声明值不符 → **删除已落盘文件**并返回 400 `file checksum mismatch`。

---

## 四、编译与运行

### 方式一：Android Studio（推荐）

1. 用 **Android Studio**（Giraffe 及以上，AGP 8.x）打开本 `android/` 目录。
2. `shared`/`harness` 为 library/JVM 模块，`tv` 为 application，`compileSdk 34`。
3. 电视端 `Run 'tv'`（Android TV / 电视盒子）。
4. 电视与手机连同一 Wi-Fi（同子网），用**手机浏览器**扫码打开电视上的上传页即可传文件。

### 方式二：命令行（本仓库已配置好工具链）

```bash
bash build/build.sh            # 编译电视端 APK
bash build/build.sh --clean    # 先 clean 再编译
```

`build/build.sh` 会自动定位 JDK / Gradle / Android SDK，并**动态探测代理**：
沙箱代理端口会随会话漂移，写死会报 `Connect to 127.0.0.1:xxxxx failed: Connection refused`，
脚本会在构建前探测代理是否真的能出数据，不能则自动直连。

### 产物

| 端 | 路径 |
|----|------|
| 电视端 | `tv/build/outputs/apk/debug/tv-debug.apk` |
| 电视端 | `tv/build/outputs/apk/debug/tv-debug.apk` |

---

## 五、验证

### 5.1 编译验证（已通过）

本工程已在本机（JDK 17 + Android SDK 34 + Gradle 8.9 + AGP 8.5.2 + Kotlin 1.9.24）成功编译，
产出两个经 Android Debug 签名的 APK。

### 5.2 协议端到端验证（已通过，13/13）

无需真机即可验证**真实的 Kotlin 实现**：

```bash
# 1) 构建 JVM harness（编译真实 tv/http 源码）
gradle :harness:installDist

# 2) 跑真实 Kotlin HTTP 服务器 + Python 客户端
bash ../verify_kotlin_http.sh
```

`harness` 模块通过 `srcDir` **直接引用** `shared` 与 `tv/http` 的源文件，
因此验证对象就是 APK 里跑的那份代码（不是复刻），Android 内置的 `org.json` 在 JVM 上用
Maven 版 `org.json:json` 替代（API 兼容）。

覆盖用例：设备信息、上传页、小文件、3MB+ 多片并发、空文件、文件清单、
**整文件哈希错误必须被拒**、**分片哈希错误必须被拒**、删除文件。

### 5.3 真机联调（已在真机通过）

在真实电视设备（VIDAA_TV / Android 9，IP `192.168.50.189`）上完成验证：

| 验证项 | 结果 |
|--------|------|
| APK 安装与启动 | ✅ `adb install` 成功，三端口（9527 / 41235 / 41236）全部监听 |
| UDP 自动发现 | ✅ 电视端每 3s 广播信标，广播名统一为「SEND FILE TO TV」，型号另由 `model` 字段携带 |
| TCP 通道多文件并发 | ✅ 4 文件（12MB + 2.4MB + 185KB + 0B）**4/4 成功**，3.8s / 3.8 MB/s |
| TCP 通道大规模并发 | ✅ 6 文件（含 8MB 大文件）**6/6 成功** |
| 文件完整性 | ✅ 真机落盘文件 SHA-256 与源文件**逐字节一致**（4/4 MATCH） |
| HTTP 通道 | ✅ **13/13 全绿**（含并发分片、错误哈希返回 400 拒绝落盘） |

一键复现：

```bash
# 需电视已开启网络 ADB（5555）并在屏幕确认授权
bash ../live_verify.sh 192.168.50.189
```

### 5.4 排障：设备屏蔽应用 logcat

部分定制 ROM（如 VIDAA）会**完全屏蔽第三方应用的 logcat 输出**，
`adb logcat | grep <TAG>` 查不到任何内容。此时改用**文件日志**取证：

电视端会把关键状态写入 `<外部私有目录>/tvserver-diag.log`
（如 `/sdcard/Android/data/com.lanfile.tv/files/tvserver-diag.log`），
用 `adb shell cat <path>` 读取。该日志记录了控制连接建立、分块校验、
`FILE_DONE` 发送、会话结束等关键节点，是定位协议问题的首选手段。

### 5.5 已修复的协议级缺陷（真机暴露）

真机联调暴露了 4 个仅在真实网络/并发下才出现的缺陷，均已修复：

1. **PROGRESS 消息格式不一致**：Android 端发 JSON 数组、Python 端按 dict 解析 →
   客户端 `states.items()` 抛 `AttributeError` → **控制连接读取线程崩溃** →
   后续 `FILE_DONE` 全部丢失（表现为「未收到完成确认」，小文件侥幸通过）。
   修复：Android 端改发 dict，Python 端兼容两种格式。
2. **控制连接读取竞态**：客户端主线程与读取线程并发 `recv_line`，
   `FramedConn._buf` 非线程安全导致消息错位。修复：读取全部收敛到单一读取线程。
3. **会话提前结束**：服务端 `notifier` 线程与 `finalizeFile` 竞态，
   在 `FILE_DONE` 尚未送达时就关闭控制连接。修复：统一由 `finalizeFile` 触发结束，
   并用 `shutdownOutput` 优雅关闭（避免 RST 截断在途数据）。
4. **进度视图自死锁**：`ProgressView` 用普通 `Lock`，
   `on_progress` 持锁后再进 `_draw()` 二次加锁 → 同线程自死锁。
   修复：改用 `RLock`。

---

## 六、权限说明

- `INTERNET`：必须的传输权限。
- `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE`：NSD 发现所需。
- Android 10+ 部分机型 NSD 发现需要 `ACCESS_FINE_LOCATION`（已在 `tv` 清单中按 `maxSdkVersion=32` 声明）。
- 接收目录使用应用私有目录 `getExternalFilesDir("received")`，无需存储权限。
- TV 端 `usesCleartextTraffic="true"`：HTTP 上传页需要明文 HTTP。

---

## 七、与 Python 协议对应点（TCP 通道，移植时务必保持）

1. **发现**：Android 用 `NsdManager` 注册/发现 `_lanfile._tcp.`，等价于 Python 的 UDP
   `DISCOVER`/`ANNOUNCE`；数据端口写在 NSD 的 TXT 记录 `dp` 中（默认 `controlPort+1`），
   HTTP 端口写在 `hp` 中。
2. **FramedSocket**：与 Python `FramedConn` 一致——`readLine()` 读取到 `\n` 后**保留残留字节**，
   供 `readExact()` 继续消费二进制。这是避免「控制行 + 二进制合并送达导致分块错位」的关键，切勿改成裸 `BufferedReader`。
3. **消息格式**：均为「一行 JSON + 换行符」；数据连接首行 `CHUNK` 头后紧跟原始字节。
4. **校验**：发送端预计算整文件 SHA-256（随 `MANIFEST` 下发）与每片 SHA-256；接收端逐片校验、
   整文件二次校验，不符回 `CHUNK_FAIL`/`FILE_DONE(ok=false)`。
5. **端口/常量**：`CONTROL_PORT=41235`、`DATA_PORT=41236`、`CHUNK_SIZE=4MiB`、`NSD_SERVICE_TYPE="_lanfile._tcp."`
   必须与 Python 端一致，否则无法互通。

---

## 八、生产化建议（当前为可运行版本，非最终上架版本）

- **电视端前台服务**：长时间接收应放到 `ForegroundService` 并展示常驻通知，避免被系统回收。
- **Android TV 形态**：将 `tv` 的 Launcher 改为 `LEANBACK_LAUNCHER`、引入 `leanback` 库做 TV 导航 UI。
- **大文件内存**：当前按 1MiB 缓冲流式读写，已支持 >2GB；若需极致性能可改 NIO `FileChannel` + `transferTo`。
- **安全性**：当前为可信局域网明文传输。若面向公网/不可信网络，建议增加传输层 TLS 或一次性 token 校验。
- **HTTP 服务器健壮性**：当前 `HttpUploadServer` 是教学级实现（单请求读全量 body 到内存，
  1 MiB 分块可接受；若调大 `HTTP_CHUNK_SIZE` 需改为流式落盘）。
