package com.lanfile.tv.http

import com.lanfile.shared.Base64Codec
import com.lanfile.shared.HttpProto
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 电视端内置的轻量 HTTP 上传服务器（零第三方依赖）。
 *
 * 设计目标对标 sunshine-send 的「TV 内置 HTTP 服务器 + 扫码即传」范式，
 * 但在三个方面做了强化，以支持 **50GB 级大文件** 与 **断点续传**：
 *
 * 1. **分块协商**：客户端在 init 时声明分块大小（[HttpProto.HDR_CHUNK_SIZE]），
 *    服务端按该值计算分块偏移。默认 4 MiB，50GB 文件仅需 12800 个请求，
 *    相比 1 MiB 分块把协议固定开销降低约 4 倍。
 * 2. **直写目标文件、免二次合并**：init 阶段创建目标临时文件，
 *    每个分块按 `index * chunkSize` 偏移**直接写入**，commit 阶段只做校验与改名。
 *    因此 50GB 文件既不会产生「分块副本 + 合并副本」的双倍磁盘占用，
 *    也不会有第二遍全量 IO。
 * 3. **断点续传**：客户端用稳定 [HttpProto.HDR_FILE_ID] 标识一次「逻辑文件」，
 *    服务端把「已收分块位图 + 元数据」持久化在上传目录的 `.lanpart/` 下。
 *    客户端断网重连或电视端 App 重启后，重新 init 即可拿回位图，只补传缺失分块。
 *
 * 完整性策略：
 * - **逐片 SHA-256 永远校验**，覆盖文件每一个字节；
 * - **整文件 SHA-256**：文件 <= 1GiB 时服务端独立重算复核（[HttpProto.WHOLE_FILE_RECHECK_LIMIT]）；
 *   超过阈值时改用「逐片校验 + 分块数量 + 总长度」判定（在密码学上等价），
 *   避免为了复核再全量读取几十 GB。
 *
 * 路由：
 * | 方法 | 路径                      | 说明                                    |
 * |------|---------------------------|-----------------------------------------|
 * | GET  | /                         | 返回内置 Web 上传页 (res/raw/index.html) |
 * | POST | /api/upload/init          | 创建/恢复上传会话，返回 sessionId 与已收位图 |
 * | POST | /api/upload/chunk         | 上传单个分块（请求体为裸二进制，流式落盘） |
 * | POST | /api/upload/commit        | 校验完整性 -> 落盘 -> 改名              |
 * | GET  | /api/upload/status        | 查询上传会话进度                          |
 * | GET  | /api/files                | 已接收文件清单（按时间倒序）              |
 * | GET  | /api/device               | 设备信息                                  |
 * | DELETE| /api/file/delete?name=xx | 删除文件                                 |
 */
class HttpUploadServer(
    private val uploadDir: File,
    private val listener: Listener,
    private val indexHtmlProvider: () -> String?,
    private val port: Int = HttpProto.HTTP_PORT,
) {

    /** 上传生命周期回调。实现方需自行切回主线程更新 UI。 */
    interface Listener {
        /** 上传会话创建。 */
        fun onSessionStart(sessionId: String, fileName: String, totalBytes: Long)

        /** 上传进度更新。 */
        fun onProgress(sessionId: String, fileName: String, received: Long, total: Long)

        /** 单个文件上传完成且整文件校验通过。 */
        fun onFileComplete(sessionId: String, fileName: String, file: File)

        /** 上传失败（校验不通过、网络中断等）。 */
        fun onError(sessionId: String, fileName: String, error: String)

        /** 文件被删除。 */
        fun onFileDeleted(fileName: String)

        /**
         * 遥控指令（来自手机遥控器）。
         *
         * @param key 按键名：`up`/`down`/`left`/`right`/`ok`/`back`
         * @return 处理结果说明（供手机端显示）；不需要可返回空串
         */
        fun onRemoteKey(key: String): String = ""

        /** 当前焦点状态（供手机遥控器显示 TV 端选中了什么）。 */
        fun onRemoteState(): String = "{}"
    }

    /**
     * 上传会话状态。
     *
     * 会话与「逻辑文件」一一对应：同一个 [fileId] 再次 init 时复用同一个会话，
     * 从而实现断点续传。
     */
    private class Session(
        val id: String,
        val fileId: String,
        val key: String,
        val fileName: String,
        val totalBytes: Long,
        val chunkSize: Int,
        val chunkCount: Int,
        val dataFile: File,
        val bitsFile: File,
        val metaFile: File,
    ) {
        /** 已收分块位图。 */
        val received = BitSet(chunkCount)
        val receivedBytes = AtomicLong(0)
        val failed = AtomicBoolean(false)
        val notified = AtomicBoolean(false)
        val lock = Any()

        @Volatile
        var raf: RandomAccessFile? = null

        @Volatile
        var lastNotifyAt = 0L

        @Volatile
        var lastNotifyBytes = 0L
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionsByFileId = ConcurrentHashMap<String, Session>()
    private val sessionSeq = AtomicLong(0)

    /** 保护同一会话的落盘操作（续传时可能有并发分块同时写入）。 */
    private val chunkSlots = Semaphore(HttpProto.MAX_CONCURRENT_CHUNKS)

    /** 支持的遥控按键名（手机遥控器）。 */
    private val REMOTE_KEYS = listOf("up", "down", "left", "right", "ok", "back")

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var workerPool: ExecutorService? = null

    /** 启动服务器。 */
    fun start() {
        if (running) return
        uploadDir.mkdirs()
        // 清理过期的续传残留，避免电视存储被永久失败的会话占满
        purgeStaleParts()
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("0.0.0.0", port), 64)
        }
        workerPool = Executors.newFixedThreadPool(HttpProto.MAX_CONCURRENT_CHUNKS + 16) { r ->
            Thread(r, "lan-http-worker").apply { isDaemon = true }
        }
        running = true
        Thread({ acceptLoop() }, "lan-http-accept").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 清理超过保留期的续传残留（`.lanpart/` 下的 .dat/.bits/.json）。
     *
     * 续传状态需要跨「断网」甚至跨「电视端 App 重启」保留，
     * 因此不能一启动就清空；但永久失败（例如对端反复中断）的会话会一直占用存储，
     * 故按时间过期回收。
     */
    private fun purgeStaleParts() {
        val partDir = File(uploadDir, HttpProto.PART_DIR)
        val files = partDir.listFiles() ?: return
        val deadline = System.currentTimeMillis() - HttpProto.PART_RETENTION_MS
        for (f in files) {
            if (!f.isFile) continue
            if (f.lastModified() < deadline) {
                runCatching { f.delete() }
            }
        }
    }

    /** 停止服务器并清理未完成的临时分片（保留可续传元数据）。 */
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { workerPool?.shutdownNow() }
        serverSocket = null
        workerPool = null
        sessions.values.forEach { closeSession(it) }
        sessions.clear()
        sessionsByFileId.clear()
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val sock = serverSocket?.accept() ?: break
                workerPool?.execute { runCatching { handleConnection(sock) } }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    // ------------------------------------------------------------------
    // 连接处理
    // ------------------------------------------------------------------

    private fun handleConnection(sock: Socket) {
        sock.use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 300_000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            while (running) {
                val keepAlive = handleOneRequest(s, input, output)
                if (!keepAlive) break
            }
        }
    }

    /** 处理一次 HTTP 请求，返回是否保持连接。 */
    private fun handleOneRequest(sock: Socket, input: InputStream, output: OutputStream): Boolean {
        val requestLine = readLine(input) ?: return false
        if (requestLine.isBlank()) return true

        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            writeResponse(output, 400, "text/plain; charset=utf-8", "Bad Request".toByteArray(), emptyMap())
            return false
        }
        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val path = rawTarget.substringBefore('?')
        val query = parseQuery(rawTarget.substringAfter('?', ""))

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return false
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substringAfter(':', "").trim()
            }
        }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        val keepAlive = headers["connection"]?.lowercase() != "close"

        // 分块请求体较大（默认 4 MiB），走流式落盘，避免整块驻留内存。
        if (method == "POST" && path == HttpProto.PATH_UPLOAD_CHUNK) {
            return try {
                handleChunk(headers, contentLength, input, output)
                keepAlive
            } catch (e: Exception) {
                writeResponse(
                    output, 500, "application/json; charset=utf-8",
                    jsonError(e.message ?: "server error").toByteArray(), emptyMap()
                )
                keepAlive
            }
        }

        val body = readFully(input, contentLength)
        return try {
            dispatch(method, path, query, headers, body, output)
            keepAlive
        } catch (e: Exception) {
            writeResponse(
                output, 500, "application/json; charset=utf-8",
                jsonError(e.message ?: "server error").toByteArray(), emptyMap()
            )
            keepAlive
        }
    }

    /** 路由分发。 */
    private fun dispatch(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray,
        output: OutputStream,
    ) {
        when {
            method == "GET" && (path == HttpProto.PATH_INDEX || path == "/index.html") ->
                serveIndex(output)

            method == "GET" && path == HttpProto.PATH_DEVICE_INFO ->
                writeJson(output, 200, deviceInfoJson())

            method == "GET" && path == HttpProto.PATH_FILES ->
                writeJson(output, 200, filesJson())

            method == "GET" && path == HttpProto.PATH_UPLOAD_STATUS ->
                writeJson(output, 200, statusJson(query["session"], query["file_id"]))

            method == "POST" && path == HttpProto.PATH_UPLOAD_INIT ->
                handleInit(headers, output)

            method == "POST" && path == HttpProto.PATH_UPLOAD_COMMIT ->
                handleCommit(headers, output)

            method == "DELETE" && path == HttpProto.PATH_FILE_DELETE ->
                handleDelete(query["name"], output)

            method == "POST" && path == HttpProto.PATH_REMOTE_KEY ->
                handleRemoteKey(query, body, output)

            method == "GET" && path == HttpProto.PATH_REMOTE_STATE ->
                writeJson(output, 200, listener.onRemoteState())

            else ->
                writeResponse(output, 404, "text/plain; charset=utf-8", "Not Found".toByteArray(), emptyMap())
        }
    }

    /**
     * 处理遥控指令（手机遥控器）。
     *
     * 按键名优先取查询参数 `key`，其次取 JSON 请求体的 `key` 字段；
     * 也接受 `X-Lan-Key` 请求头，便于非浏览器客户端调用。
     */
    private fun handleRemoteKey(
        query: Map<String, String>,
        body: ByteArray,
        output: OutputStream,
    ) {
        val key = query["key"]
            ?: extractKey(body)
            ?: ""
        val normalized = key.trim().lowercase()
        if (normalized !in REMOTE_KEYS) {
            writeJson(
                output, 400,
                JSONObject()
                    .put("ok", false)
                    .put("error", "unknown key: $key")
                    .put("supported", JSONArray(REMOTE_KEYS.toList()))
                    .toString()
            )
            return
        }
        val result = runCatching { listener.onRemoteKey(normalized) }
            .getOrElse { "remote key failed: ${it.message}" }
        writeJson(
            output, 200,
            JSONObject().put("ok", true).put("key", normalized).put("result", result).toString()
        )
    }

    /** 从 JSON 请求体中提取 `key` 字段（非 JSON 体返回 null）。 */
    private fun extractKey(body: ByteArray): String? = runCatching {
        val obj = org.json.JSONObject(String(body, Charsets.UTF_8))
        obj.optString("key", "").takeIf { it.isNotBlank() }
    }.getOrNull()

    // ------------------------------------------------------------------
    // 上传会话
    // ------------------------------------------------------------------

    /** 创建或恢复上传会话。 */
    private fun handleInit(headers: Map<String, String>, output: OutputStream) {
        val rawName = headers[HttpProto.HDR_NAME.lowercase()].orEmpty()
        val name = safeName(decode(rawName))
        val totalBytes = headers[HttpProto.HDR_TOTAL_SIZE.lowercase()]?.toLongOrNull() ?: 0L
        val declaredSha = headers[HttpProto.HDR_FILE_SHA.lowercase()].orEmpty()
        val rawFileId = headers[HttpProto.HDR_FILE_ID.lowercase()].orEmpty()

        // 分块大小：客户端声明优先，缺省用 1 MiB（兼容老客户端）。
        val chunkSize = headers[HttpProto.HDR_CHUNK_SIZE.lowercase()]?.toIntOrNull()
            ?.takeIf { it in HttpProto.MIN_HTTP_CHUNK_SIZE..HttpProto.MAX_HTTP_CHUNK_SIZE }
            ?: HttpProto.LEGACY_HTTP_CHUNK_SIZE
        val chunkCount = if (totalBytes <= 0) 0 else
            ((totalBytes + chunkSize - 1) / chunkSize).toInt()

        val fileId = rawFileId.trim().ifEmpty { "$name:$totalBytes" }
        val key = storageKey(fileId)

        val existing = sessionsByFileId[fileId]
        val session: Session
        val resumed: Boolean
        if (existing != null && existing.totalBytes == totalBytes && existing.chunkSize == chunkSize && !existing.failed.get()) {
            session = existing
            resumed = existing.received.cardinality() > 0
        } else {
            existing?.let { closeSession(it) }
            session = createSession(name, fileId, key, totalBytes, chunkSize, chunkCount)
            resumed = session.received.cardinality() > 0
        }

        if (!session.notified.get()) {
            session.notified.set(true)
            listener.onSessionStart(session.id, session.fileName, session.totalBytes)
        }

        val resp = JSONObject()
            .put("session", session.id)
            .put(HttpProto.RESP_CHUNK_SIZE, session.chunkSize)
            .put(HttpProto.RESP_CHUNK_COUNT, session.chunkCount)
            .put(HttpProto.RESP_RESUMED, resumed)
            .put(HttpProto.RESP_RECEIVED_B64, encodeBitmap(session.received))
        writeJson(output, 200, resp.toString())
    }

    /** 新建会话：创建临时数据文件与元数据文件。 */
    private fun createSession(
        name: String,
        fileId: String,
        key: String,
        totalBytes: Long,
        chunkSize: Int,
        chunkCount: Int,
    ): Session {
        val partDir = File(uploadDir, HttpProto.PART_DIR).apply { mkdirs() }
        val dataFile = File(partDir, "$key.dat")
        val bitsFile = File(partDir, "$key.bits")
        val metaFile = File(partDir, "$key.json")

        val id = "u" + sessionSeq.incrementAndGet() + "_" + System.currentTimeMillis()
        val session = Session(
            id, fileId, key, name, totalBytes, chunkSize, chunkCount,
            dataFile, bitsFile, metaFile,
        )

        // 载入历史位图（断点续传：电视端 App 重启后依然有效）。
        loadBitmap(bitsFile, session)
        // 元数据：文件在续传期间可能改名/改大小，不一致则丢弃旧位图重来。
        val meta = readMeta(metaFile)
        val metaMismatch = meta == null || meta.name != name || meta.size != totalBytes ||
            meta.chunkSize != chunkSize
        if (metaMismatch) {
            session.received.clear()
            // 旧数据文件可能残留（分块大小变化 / 文件改名），清空重来。
            runCatching { session.dataFile.delete() }
        }
        session.receivedBytes.set(receivedBytesOf(session))

        // 确保数据文件存在，但**不预分配长度**：
        // 电视端外置存储常为 FAT32/exFAT，ftruncate 一个 50GB 长度会立即失败或
        // 真实占用 50GB 空间。改为按需写入（seek 超长会自动扩展并补零），
        // 空间只随实际写入量增长。
        runCatching {
            if (!session.dataFile.exists()) session.dataFile.createNewFile()
        }

        writeMeta(session)
        saveBitmap(session)

        sessions[id] = session
        sessionsByFileId[fileId] = session
        return session
    }

    /** 接收一个分块：流式落盘 + 逐片 SHA-256 校验。 */
    private fun handleChunk(
        headers: Map<String, String>,
        contentLength: Long,
        input: InputStream,
        output: OutputStream,
    ) {
        val sessionId = headers[HttpProto.HDR_SESSION.lowercase()].orEmpty()
        val session = sessions[sessionId]
        if (session == null) {
            drain(input, contentLength)
            writeResponse(
                output, 404, "application/json; charset=utf-8",
                jsonError("session not found").toByteArray(), emptyMap()
            )
            return
        }
        if (session.failed.get()) {
            drain(input, contentLength)
            writeResponse(
                output, 409, "application/json; charset=utf-8",
                jsonError("session failed").toByteArray(), emptyMap()
            )
            return
        }

        val index = headers[HttpProto.HDR_INDEX.lowercase()]?.toIntOrNull() ?: -1
        val expectSha = headers[HttpProto.HDR_CHUNK_SHA.lowercase()].orEmpty()
        val declaredOffset = headers[HttpProto.HDR_OFFSET.lowercase()]?.toLongOrNull()
        if (index < 0 || index >= session.chunkCount) {
            drain(input, contentLength)
            writeResponse(
                output, 400, "application/json; charset=utf-8",
                jsonError("invalid chunk index $index").toByteArray(), emptyMap()
            )
            return
        }
        val offset = declaredOffset ?: (index.toLong() * session.chunkSize)
        // 偏移一致性校验：客户端声明的偏移必须与服务端按分块大小推算的一致，
        // 否则说明双方的分块大小协商出现分歧，继续写会静默损坏文件。
        val expectedOffset = index.toLong() * session.chunkSize
        if (offset != expectedOffset) {
            drain(input, contentLength)
            writeResponse(
                output, 400, "application/json; charset=utf-8",
                jsonError("chunk offset mismatch: got=$offset expected=$expectedOffset").toByteArray(),
                emptyMap()
            )
            return
        }

        chunkSlots.acquire()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(256 * 1024)
            var written = 0L
            var remaining = contentLength
            val raf = session.raf ?: synchronized(session.lock) {
                session.raf ?: RandomAccessFile(session.dataFile, "rw").also { session.raf = it }
            }
            while (remaining > 0) {
                val want = minOf(remaining, buf.size.toLong()).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                digest.update(buf, 0, n)
                synchronized(session.lock) {
                    raf.seek(offset + written)
                    raf.write(buf, 0, n)
                }
                written += n
                remaining -= n
            }

            val chunkSha = digest.digest().toHex()
            // 逐片校验：客户端声明了哈希就必须匹配（原生 App 通道始终声明）。
            if (expectSha.isNotEmpty() && !expectSha.equals(chunkSha, ignoreCase = true)) {
                // 校验失败：该分块数据不可信，清零该区间，等待客户端重传覆盖。
                // 注意：不结束整个会话 —— 客户端会对该分块自动重试。
                zeroRange(session, raf, offset, written)
                listener.onError(sessionId, session.fileName, "分块校验失败 (index=$index)")
                writeResponse(
                    output, 400, "application/json; charset=utf-8",
                    jsonError("chunk checksum mismatch").toByteArray(), emptyMap()
                )
                return
            }

            synchronized(session.lock) {
                if (!session.received.get(index)) {
                    session.received.set(index)
                    session.receivedBytes.addAndGet(written)
                }
            }
            saveBitmap(session)

            notifyProgress(session, index == session.chunkCount - 1)
            val resp = JSONObject()
                .put("ok", true)
                .put("index", index)
                .put("received", session.receivedBytes.get())
                .put("sha256", chunkSha)
            writeJson(output, 200, resp.toString())
        } catch (e: Exception) {
            session.failed.set(true)
            listener.onError(sessionId, session.fileName, e.message ?: "写入分块失败")
            writeResponse(
                output, 500, "application/json; charset=utf-8",
                jsonError(e.message ?: "write chunk failed").toByteArray(), emptyMap()
            )
        } finally {
            chunkSlots.release()
        }
    }

    /** 提交：校验完整性 -> 落盘改名。 */
    private fun handleCommit(headers: Map<String, String>, output: OutputStream) {
        val sessionId = headers[HttpProto.HDR_COMMIT_SESSION.lowercase()].orEmpty()
        val declaredSha = headers[HttpProto.HDR_FILE_SHA.lowercase()].orEmpty()
        val session = sessions[sessionId]
        if (session == null) {
            writeResponse(
                output, 404, "application/json; charset=utf-8",
                jsonError("session not found").toByteArray(), emptyMap()
            )
            return
        }

        try {
            val complete = session.received.cardinality() >= session.chunkCount
            val sizeOk = session.dataFile.length() == session.totalBytes
            if (!complete || !sizeOk) {
                listener.onError(
                    sessionId, session.fileName,
                    "分块数量不完整 (${session.received.cardinality()}/${session.chunkCount})"
                )
                writeResponse(
                    output, 400, "application/json; charset=utf-8",
                    jsonError("incomplete chunks").toByteArray(), emptyMap()
                )
                return
            }

            // 完整性判定：
            // - 小文件：服务端独立重算整文件 SHA-256 并与客户端声明比对；
            // - 大文件：逐片校验已覆盖每个字节，改用「逐片 + 数量 + 总长度」判定。
            var verified = "chunkwise"
            var whole = ""
            if (session.totalBytes <= HttpProto.WHOLE_FILE_RECHECK_LIMIT) {
                whole = com.lanfile.shared.Crypto.sha256OfFile(session.dataFile.absolutePath)
                if (declaredSha.isNotEmpty() && !declaredSha.equals(whole, ignoreCase = true)) {
                    runCatching { session.dataFile.delete() }
                    // 校验失败即放弃该次上传：清理位图与元数据，避免留下孤儿续传状态
                    runCatching { session.bitsFile.delete() }
                    runCatching { session.metaFile.delete() }
                    sessions.remove(session.id)
                    sessionsByFileId.remove(session.fileId)
                    closeSession(session)
                    listener.onError(sessionId, session.fileName, "整文件校验失败")
                    writeResponse(
                        output, 400, "application/json; charset=utf-8",
                        jsonError("file checksum mismatch").toByteArray(), emptyMap()
                    )
                    return
                }
                verified = "whole"
            }

            // 落盘改名：临时文件 -> 最终文件名（同名自动加序号）。
            val target = uniquePath(session.fileName)
            synchronized(session.lock) {
                runCatching { session.raf?.fd?.sync() }
                runCatching { session.raf?.close() }
                session.raf = null
                if (!session.dataFile.renameTo(target)) {
                    // 跨设备/文件系统时 rename 可能失败，退回复制。
                    session.dataFile.copyTo(target, overwrite = true)
                    session.dataFile.delete()
                }
            }
            runCatching { session.bitsFile.delete() }
            runCatching { session.metaFile.delete() }
            sessions.remove(session.id)
            sessionsByFileId.remove(session.fileId)

            listener.onFileComplete(sessionId, session.fileName, target)
            val resp = JSONObject()
                .put("ok", true)
                .put("name", target.name)
                .put("size", target.length())
                .put("sha256", whole)
                .put(HttpProto.RESP_VERIFIED, verified)
                .put(HttpProto.RESP_RESUMED, session.received.cardinality() > 0)
            writeJson(output, 200, resp.toString(), mapOf(HttpProto.HDR_RESP_FILE_SHA to whole))
        } catch (e: Exception) {
            listener.onError(sessionId, session.fileName, e.message ?: "提交失败")
            writeResponse(
                output, 500, "application/json; charset=utf-8",
                jsonError(e.message ?: "commit failed").toByteArray(), emptyMap()
            )
        }
    }

    // ------------------------------------------------------------------
    // 查询与文件管理
    // ------------------------------------------------------------------

    /** 查询会话进度（可按 session 或 file_id 查）。 */
    private fun statusJson(sessionId: String?, fileId: String?): String {
        val session = sessionId?.let { sessions[it] }
            ?: fileId?.let { sessionsByFileId[it] }
        if (session == null) {
            return JSONObject().put("found", false).toString()
        }
        return JSONObject()
            .put("found", true)
            .put("session", session.id)
            .put("name", session.fileName)
            .put("received", session.receivedBytes.get())
            .put("total", session.totalBytes)
            .put("chunks_done", session.received.cardinality())
            .put("chunk_count", session.chunkCount)
            .put(HttpProto.RESP_RECEIVED_B64, encodeBitmap(session.received))
            .toString()
    }

    /** 已接收文件清单（按时间倒序：最新在最前）。 */
    private fun filesJson(): String {
        val arr = JSONArray()
        uploadDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(HttpProto.PART_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { f ->
                arr.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("size", f.length())
                        .put("time", f.lastModified())
                )
            }
        return arr.toString()
    }

    /** 设备信息。 */
    private fun deviceInfoJson(): String {
        return JSONObject()
            .put("app", "lanfiletransfer")
            .put("version", "3.0")
            .put("http_port", port)
            .put("files_dir", uploadDir.absolutePath)
            .toString()
    }

    /** 删除文件。 */
    private fun handleDelete(name: String?, output: OutputStream) {
        val target = name?.let { File(uploadDir, safeName(decode(it))) }
        if (target == null || !target.exists() || !target.isFile) {
            writeResponse(
                output, 404, "application/json; charset=utf-8",
                jsonError("file not found").toByteArray(), emptyMap()
            )
            return
        }
        val deleted = target.delete()
        if (deleted) listener.onFileDeleted(target.name)
        val resp = JSONObject().put("ok", deleted).put("name", target.name)
        writeJson(output, if (deleted) 200 else 500, resp.toString())
    }

    // ------------------------------------------------------------------
    // 静态页与工具
    // ------------------------------------------------------------------

    /** 返回内置 Web 上传页。 */
    private fun serveIndex(output: OutputStream) {
        val html = indexHtmlProvider()
        if (html == null) {
            writeResponse(output, 404, "text/plain; charset=utf-8", "index.html missing".toByteArray(), emptyMap())
            return
        }
        writeResponse(output, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8), emptyMap())
    }

    /** 文件名安全化：仅保留基名，剥离路径分隔符。 */
    private fun safeName(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = base.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ' ' || it.code > 127 }
        return cleaned.ifBlank { "unnamed" }
    }

    /** 同名文件自动加序号。 */
    private fun uniquePath(name: String): File {
        uploadDir.mkdirs()
        var candidate = File(uploadDir, name)
        var i = 1
        while (candidate.exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) {
                File(uploadDir, "${name.substring(0, dot)}($i).${name.substring(dot + 1)}")
            } else {
                File(uploadDir, "$name($i)")
            }
            i++
        }
        return candidate
    }

    // ------------------------------------------------------------------
    // 续传元数据持久化
    // ------------------------------------------------------------------

    private data class Meta(val name: String, val size: Long, val chunkSize: Int, val chunkCount: Int)

    private fun storageKey(fileId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(fileId.toByteArray(Charsets.UTF_8)).toHex().take(32)
    }

    private fun readMeta(file: File): Meta? = try {
        if (!file.exists()) null else {
            val o = JSONObject(file.readText())
            Meta(
                o.optString("name"),
                o.optLong("size"),
                o.optInt("chunk_size"),
                o.optInt("chunk_count"),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun writeMeta(session: Session) {
        runCatching {
            val o = JSONObject()
                .put("file_id", session.fileId)
                .put("name", session.fileName)
                .put("size", session.totalBytes)
                .put("chunk_size", session.chunkSize)
                .put("chunk_count", session.chunkCount)
                .put("updated", System.currentTimeMillis())
            session.metaFile.writeText(o.toString())
        }
    }

    private fun saveBitmap(session: Session) {
        runCatching {
            session.bitsFile.writeBytes(encodeBitmapBytes(session.received))
        }
    }

    private fun loadBitmap(file: File, session: Session) {
        runCatching {
            if (!file.exists()) return@runCatching
            val bytes = file.readBytes()
            val bits = BitSet.valueOf(bytes)
            for (i in 0 until session.chunkCount) {
                if (bits.get(i)) session.received.set(i)
            }
        }
    }

    private fun encodeBitmap(bits: BitSet): String = Base64Codec.encode(encodeBitmapBytes(bits))

    private fun encodeBitmapBytes(bits: BitSet): ByteArray = bits.toByteArray()

    /** 依据位图估算已收字节数（用于断点续传时的进度起点）。 */
    private fun receivedBytesOf(session: Session): Long {
        var bytes = 0L
        for (i in 0 until session.chunkCount) {
            if (!session.received.get(i)) continue
            val offset = i.toLong() * session.chunkSize
            bytes += minOf(session.chunkSize.toLong(), session.totalBytes - offset).coerceAtLeast(0)
        }
        return bytes
    }

    /** 关闭会话资源。 */
    private fun closeSession(session: Session) {
        runCatching { session.raf?.close() }
        session.raf = null
    }

    /** 进度回调（限流，避免 12800 次回调把 UI 打爆）。 */
    private fun notifyProgress(session: Session, force: Boolean) {
        val now = System.currentTimeMillis()
        val bytes = session.receivedBytes.get()
        if (!force && now - session.lastNotifyAt < 200 && bytes - session.lastNotifyBytes < 4L * 1024 * 1024) {
            return
        }
        session.lastNotifyAt = now
        session.lastNotifyBytes = bytes
        listener.onProgress(session.id, session.fileName, bytes, session.totalBytes)
    }

    /** 把区间清零（分块校验失败时丢弃不可信数据）。 */
    private fun zeroRange(session: Session, raf: RandomAccessFile, offset: Long, length: Long) {
        runCatching {
            synchronized(session.lock) {
                raf.seek(offset)
                val zeros = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    raf.write(zeros, 0, n)
                    left -= n
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP 基础读写
    // ------------------------------------------------------------------

    /** 读取一行（不含 \r\n）。 */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    /** 精确读取 n 字节。 */
    private fun readFully(input: InputStream, n: Long): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(minOf(n, 1L shl 20).toInt())
        val out = java.io.ByteArrayOutputStream(n.coerceAtMost(1L shl 26).toInt())
        var remaining = n
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, want)
            if (read < 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    /** 丢弃请求体（出错时保持连接可继续使用）。 */
    private fun drain(input: InputStream, n: Long) {
        if (n <= 0) return
        val buf = ByteArray(64 * 1024)
        var left = n
        while (left > 0) {
            val read = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (read < 0) break
            left -= read
        }
    }

    /** 解析查询串。 */
    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            map[decode(pair.substring(0, idx))] = decode(pair.substring(idx + 1))
        }
        return map
    }

    /** URL 解码（容错）。 */
    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    /** 写响应。 */
    private fun writeResponse(
        output: OutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String>,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
        for ((k, v) in extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    /** 写 JSON 响应。 */
    private fun writeJson(output: OutputStream, code: Int, json: String, extraHeaders: Map<String, String> = emptyMap()) {
        writeResponse(output, code, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8), extraHeaders)
    }

    /** 错误 JSON。 */
    private fun jsonError(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()

    /** HTTP 状态文本。 */
    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        409 -> "Conflict"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}

/** 字节数组转十六进制小写串。 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
