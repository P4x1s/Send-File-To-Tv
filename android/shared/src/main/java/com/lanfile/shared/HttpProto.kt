package com.lanfile.shared

/**
 * HTTP 传输通道协议常量（对标 sunshine-send 的「TV 端内置 HTTP 服务器」范式）。
 *
 * 与原有 TCP 线协议（[Protocol.kt]）并存，互不冲突：
 * - TCP 通道：手机原生 App -> 电视端，分块并发 + 逐片 SHA256 校验（跨语言互操作已验证）；
 * - HTTP 通道：手机浏览器 / 原生 App -> 电视端内置 HTTP 服务器，用于「扫码即传」零安装场景。
 *
 * 两者共用同一套分块规划与校验算法（[ChunkPlanner.kt] / [Crypto.kt]），因此
 * 通过 HTTP 通道上传的文件同样具备逐片 + 整文件双重 SHA256 完整性保障。
 *
 * ## 大文件（50GB 级）与断点续传设计
 *
 * 1. **分块协商**：客户端在 init 阶段通过 [HDR_CHUNK_SIZE] 声明分块大小，
 *    服务端按该值计算分块偏移（不再固定 1 MiB），分块越大，请求数越少，
 *    50 GB 文件在 4 MiB 分块下仅 12800 个请求。
 * 2. **直写目标文件**：服务端在 init 时预分配目标文件（稀疏文件），
 *    每个分块按 `index * chunkSize` 偏移直接写入，commit 时无需二次合并，
 *    因此 50 GB 文件不会产生「分块副本 + 合并副本」的双倍磁盘占用与双倍 IO。
 * 3. **断点续传**：客户端用稳定的 [HDR_FILE_ID]（文件名 + 大小 + 修改时间派生）
 *    标识一次「逻辑文件」，服务端把已收分块位图与元数据持久化到 `.lanpart/` 目录，
 *    客户端重新连接时 init 会拿回已收位图（[RESP_RECEIVED_B64]）并只补传缺失分块。
 *    电视端 App 重启后续传状态依然有效。
 * 4. **完整性**：逐片 SHA-256 永远校验（覆盖文件每一个字节），
 *    整文件 SHA-256 在小文件上由服务端独立重算复核；超大文件改为「逐片校验 + 总长度校验」
 *    （在密码学上与整文件哈希等价），避免为了复核再全量读一遍 50 GB。
 */
object HttpProto {

    /** 电视端内置 HTTP 服务器监听端口（与 sunshine-send 的 9527 保持一致，便于交叉调试）。 */
    const val HTTP_PORT = 9527

    /**
     * HTTP 通道的默认分块大小（4 MiB）。
     *
     * 相比 1 MiB，4 MiB 可把 50 GB 文件的 HTTP 请求数从 51200 降到 12800，
     * 请求头/握手/校验等固定开销随之下降约 4 倍，是大文件吞吐的关键。
     */
    const val HTTP_CHUNK_SIZE = 4 * 1024 * 1024

    /** 允许的最小分块大小（小文件自适应，避免最后一包过碎）。 */
    const val MIN_HTTP_CHUNK_SIZE = 256 * 1024

    /**
     * 旧版客户端使用的分块大小（1 MiB）。
     *
     * 服务端在客户端**未声明** [HDR_CHUNK_SIZE] 时回退到该值，
     * 从而保持与历史客户端（浏览器页 v1 / Python 参考实现）的兼容。
     */
    const val LEGACY_HTTP_CHUNK_SIZE = 1024 * 1024

    /** 服务端接受的客户端声明分块大小上限（防止恶意/异常客户端放大内存占用）。 */
    const val MAX_HTTP_CHUNK_SIZE = 16 * 1024 * 1024

    /** HTTP 通道的默认并发线程数（单文件内部的分块并发度）。 */
    const val DEFAULT_HTTP_THREADS = 8

    /** 客户端同时传输的**文件数**上限（几百个文件时最多 5 个并行，其余排队）。 */
    const val MAX_CONCURRENT_FILES = 5

    /** 服务端同时处理的**分块请求**上限（保护电视端 CPU / 内存）。 */
    const val MAX_CONCURRENT_CHUNKS = 32

    /**
     * 手机端全局分块槽位上限（跨所有文件共享）。
     *
     * 分块在「读取前」占用槽位、发送完成后释放，因此驻留内存约为
     * `CLIENT_CHUNK_SLOTS × 分块大小`（默认 8 × 4 MiB = 32 MiB），
     * 与文件数量无关 —— 这是 50GB 多文件并发不 OOM 的关键。
     */
    const val CLIENT_CHUNK_SLOTS = 8

    /**
     * 服务端「独立重算整文件 SHA-256」的文件大小上限（1 GiB）。
     *
     * 超过该阈值时，服务端改为依赖「逐片 SHA-256 + 分块数量 + 总长度」判定完整性，
     * 避免为了复核而额外全量读取几十 GB 数据。
     */
    const val WHOLE_FILE_RECHECK_LIMIT = 1024L * 1024 * 1024

    /** 上传分块的临时文件后缀（未 commit 前存在，commit 成功后删除）。 */
    const val PART_SUFFIX = ".lanpart"

    /** 续传元数据目录名（位于上传目录内部）。 */
    const val PART_DIR = ".lanpart"

    /**
     * 续传残留的保留时长（24 小时）。
     *
     * 续传状态需跨断网、跨电视端 App 重启保留，因此不能立即清理；
     * 但永久失败的会话会持续占用电视存储，故设置过期回收。
     */
    const val PART_RETENTION_MS = 24L * 60 * 60 * 1000

    // ---- 路由 ----

    /** 上传页（Web 界面）。 */
    const val PATH_INDEX = "/"

    /** 创建上传会话：返回 sessionId 与分块大小。 */
    const val PATH_UPLOAD_INIT = "/api/upload/init"

    /** 上传单个分块（请求体为裸二进制）。 */
    const val PATH_UPLOAD_CHUNK = "/api/upload/chunk"

    /** 提交上传：服务端合并分块并做整文件校验。 */
    const val PATH_UPLOAD_COMMIT = "/api/upload/commit"

    /** 查询某个上传会话的进度。 */
    const val PATH_UPLOAD_STATUS = "/api/upload/status"

    /** 查询已接收文件清单。 */
    const val PATH_FILES = "/api/files"

    /** 删除已接收文件。 */
    const val PATH_FILE_DELETE = "/api/file/delete"

    /** 电视端设备信息（供手机端展示与自检）。 */
    const val PATH_DEVICE_INFO = "/api/device"

    /**
     * 遥控指令（手机遥控器）：POST，按键名放在请求体或 `key` 查询参数。
     *
     * 取值：`up` / `down` / `left` / `right` / `ok` / `back`。
     */
    const val PATH_REMOTE_KEY = "/api/remote/key"

    /** 查询电视端当前焦点状态（当前选中项、文件数）。 */
    const val PATH_REMOTE_STATE = "/api/remote/state"

    // ---- 请求头 ----

    /** 分块请求头：会话 ID。 */
    const val HDR_SESSION = "X-Lan-Session"

    /** 分块请求头：分块序号。 */
    const val HDR_INDEX = "X-Lan-Index"

    /** 分块请求头：分块字节数。 */
    const val HDR_SIZE = "X-Lan-Size"

    /** 分块请求头：分块 SHA256（浏览器端无法计算，故为可选头）。 */
    const val HDR_CHUNK_SHA = "X-Lan-Chunk-Sha256"

    /** 分块请求头：分块在文件中的字节偏移（显式声明，避免依赖 chunkSize 推算）。 */
    const val HDR_OFFSET = "X-Lan-Offset"

    /** init 请求头：文件名（URL 编码）。 */
    const val HDR_NAME = "X-Lan-Name"

    /** init 请求头：整文件字节数。 */
    const val HDR_TOTAL_SIZE = "X-Lan-Total-Size"

    /** init 请求头：整文件 SHA256（可选，浏览器端无法计算）。 */
    const val HDR_FILE_SHA = "X-Lan-File-Sha256"

    /** init 请求头：总分块数。 */
    const val HDR_CHUNK_COUNT = "X-Lan-Chunk-Count"

    /** init 请求头：分块大小（客户端声明，服务端据此计算偏移）。 */
    const val HDR_CHUNK_SIZE = "X-Lan-Chunk-Size"

    /**
     * init 请求头：逻辑文件 ID（客户端派生，断点续传的关键）。
     *
     * 约定为 `文件名:字节数:最后修改时间` 的稳定摘要，服务端据此在磁盘上
     * 找回上次未完成的分块，从而实现跨断网、跨 App 重启的续传。
     */
    const val HDR_FILE_ID = "X-Lan-File-Id"

    /** commit 请求头：会话 ID。 */
    const val HDR_COMMIT_SESSION = "X-Lan-Commit-Session"

    /** 响应头：整个文件的 SHA256（commit 成功后返回）。 */
    const val HDR_RESP_FILE_SHA = "X-Lan-Resp-Sha256"

    /** 响应头：错误描述。 */
    const val HDR_RESP_ERROR = "X-Lan-Resp-Error"

    // ---- 响应体字段 ----

    /** init 响应字段：已收分块位图（Base64 编码的 BitSet，字节序低位在前）。 */
    const val RESP_RECEIVED_B64 = "received_b64"

    /** init 响应字段：本次是否复用了历史会话（true = 断点续传）。 */
    const val RESP_RESUMED = "resumed"

    /** init 响应字段：分块大小。 */
    const val RESP_CHUNK_SIZE = "chunk_size"

    /** init 响应字段：分块总数。 */
    const val RESP_CHUNK_COUNT = "chunk_count"

    /** commit 响应字段：完整性判定方式（"whole" = 服务端独立重算整文件哈希；"chunkwise" = 逐片校验）。 */
    const val RESP_VERIFIED = "verified"
}
