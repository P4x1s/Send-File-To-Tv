package com.lanfile.phone

import android.net.Uri
import com.lanfile.shared.Base64Codec
import com.lanfile.shared.HttpProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.atomic.AtomicLong

/**
 * 待发送文件。
 *
 * 直接持有系统选择器返回的 **content URI**，不做本地拷贝：
 * 50GB 文件若先复制到缓存目录会直接撑爆手机存储，
 * 因此改为「顺序流式读取 + 直接上传」。
 *
 * @property uri 内容 URI
 * @property name 显示名
 * @property size 字节数
 * @property lastModified 最后修改时间（毫秒）
 */
data class PendingFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long,
) {
    /**
     * 稳定的逻辑文件 ID：同一份文件重复选择时保持不变，
     * 电视端据此找回已传分块，实现断点续传。
     */
    val fileId: String by lazy { hashId("$name|$size|$lastModified") }

    private fun hashId(raw: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    }
}

/** 单个文件的传输结果。 */
data class FileResult(val name: String, val ok: Boolean, val error: String, val sha256: String)

/** 单个文件的实时进度快照。 */
data class FileProgress(
    val name: String,
    val sent: Long,
    val total: Long,
    val resumedFrom: Long = 0L,
    val done: Boolean = false,
    val ok: Boolean = false,
    val error: String = "",
)

/**
 * 手机端 HTTP 上传客户端（对标 sunshine-send 的「扫码即传」通道）。
 *
 * 与 TCP 通道（[TransferClient]）采用完全一致的完整性策略：
 * - 逐片 SHA-256 校验：每片在 HTTP 请求头中携带该片哈希，服务端比对；
 * - 整文件 SHA-256 校验：commit 时客户端声明整文件哈希，
 *   服务端对小文件独立重算复核，对超大文件改用逐片校验（见 HttpUploadServer 注释）。
 *
 * ## 大文件（50GB 级）关键设计
 *
 * 1. **单遍 IO 流水线**：一个「顺序读取器」按偏移顺序读文件，边读边算
 *    「分片 SHA-256」与「整文件 SHA-256」，把分片投递到有界队列；
 *    N 个「发送器」并行发 HTTP 请求。
 *    => 文件只被读一遍，磁盘 IO 不翻倍，同时保持多路并发传输。
 * 2. **4 MiB 分块**：50GB 文件仅 12800 个请求，协议固定开销降到 1 MiB 方案的 1/4。
 * 3. **最多 5 个文件同时传输**：文件级信号量限制并发文件数，
 *    分块级再有一层全局连接预算，几百个文件排队即可。
 * 4. **断点续传**：init 时携带稳定 fileId，服务端返回已收分块位图，
 *    客户端只补传缺失分片。
 * 5. **内存有界**：分块在「读取前」先占用全局槽位，发送完成才释放，
 *    因此无论多少文件并发，驻留内存 ≈ 槽位数 × 分块大小（默认 8 × 4 MiB = 32 MiB）。
 *
 * 典型用法：
 * ```kotlin
 * val client = HttpTransferClient("192.168.1.23", HttpProto.HTTP_PORT, resolver)
 * client.transfer(files) { p -> updateUi(p) }
 * ```
 */
class HttpTransferClient(
    private val host: String,
    private val port: Int = HttpProto.HTTP_PORT,
    private val chunkThreads: Int = HttpProto.DEFAULT_HTTP_THREADS,
    private val fileConcurrency: Int = HttpProto.MAX_CONCURRENT_FILES,
    private val context: android.content.Context? = null,
) {

    private val baseUrl = "http://$host:$port"

    /** 全局分块槽位预算（跨文件共享，限制驻留内存与并发连接数）。 */
    private val globalSlots = Semaphore(HttpProto.CLIENT_CHUNK_SLOTS)

    /**
     * 上传多个文件：文件级并发最多 [fileConcurrency]（5）个，
     * 每个文件内部再做分块并发。
     *
     * @param files 待上传的文件列表
     * @param onProgress 进度回调（工作线程触发，调用方需自行切主线程）
     * @return 每个文件的结果列表（与入参顺序一致）
     */
    suspend fun transfer(
        files: List<PendingFile>,
        onProgress: (FileProgress) -> Unit = { },
    ): List<FileResult> = coroutineScope {
        val fileSlots = Semaphore(fileConcurrency)
        files.map { file ->
            async {
                fileSlots.withPermit { uploadOne(file, onProgress) }
            }
        }.awaitAll()
    }

    /** 上传单个文件（内部含「读取器 + 发送器」流水线）。 */
    private suspend fun uploadOne(
        file: PendingFile,
        onProgress: (FileProgress) -> Unit,
    ): FileResult = withContext(Dispatchers.IO) {
        val sent = AtomicLong(0L)
        try {
            val size = file.size
            val wantChunkSize = chooseChunkSize(size)

            // 1) 创建/恢复上传会话（服务端会回传已收分块位图）
            //    服务端可能把分块大小回退为 LEGACY_HTTP_CHUNK_SIZE（例如老版本电视端），
            //    此时必须**以服务端返回的 chunk_size 为准**，否则偏移计算会错位。
            val first = initSession(file, size, wantChunkSize, 0)
            val chunkSize = first.chunkSize
            val chunkCount = first.chunkCount
            // 若服务端采用了不同的分块大小，用一致的分块数重新 init
            // （服务端按 fileId 复用同一会话，会原样返回上次的位图，不丢进度）。
            val init = if (first.rechunked) initSession(file, size, chunkSize, chunkCount) else first
            val skip = decodeBitmap(init.receivedB64)
            val skipped = skip.cardinality()
            val resumedBytes = skipped * chunkSize.toLong()

            if (skipped > 0) {
                onProgress(FileProgress(file.name, resumedBytes, size, resumedBytes))
            }

            // 3) 流水线：顺序读取器 -> 有界队列 -> 并行发送器
            val queue = Channel<Chunk>(capacity = 1)
            val whole = MessageDigest.getInstance("SHA-256")
            val hashComplete = AtomicLong(1) // 是否有分片被跳过（跳过则整文件哈希不完整）
            val source = openSource(file)

            coroutineScope {
                val workers = List(chunkThreads) {
                    launch {
                        for (chunk in queue) {
                            try {
                                uploadChunkWithRetry(init.session, chunk)
                            } finally {
                                globalSlots.release()
                            }
                            val total = sent.addAndGet(chunk.size.toLong())
                            onProgress(FileProgress(file.name, total, size, resumedBytes))
                        }
                    }
                }

                try {
                    produceChunks(source, file, chunkSize, chunkCount, skip, whole, hashComplete) { chunk ->
                        queue.send(chunk)
                    }
                } finally {
                    queue.close()
                }
                // coroutineScope 会等待所有 worker 结束
            }

            // 4) 提交（声明整文件 SHA-256；续传跳过分片时不再声明，由服务端按逐片校验判定）
            val fileSha = if (hashComplete.get() == 1L) whole.digest().toHex() else ""
            val serverSha = commitSession(init.session, fileSha)
            onProgress(FileProgress(file.name, size, size, resumedBytes, done = true, ok = true))
            FileResult(file.name, ok = true, error = "", sha256 = serverSha.ifEmpty { fileSha })
        } catch (e: Exception) {
            onProgress(FileProgress(file.name, sent.get(), file.size, done = true, ok = false, error = e.message ?: "upload failed"))
            FileResult(file.name, ok = false, error = e.message ?: "upload failed", sha256 = "")
        }
    }

    /**
     * 数据源。
     *
     * @property channel 可读通道（可 seek 时用 position 定位；不可 seek 时只能顺序读）
     * @property seekable 是否支持随机定位
     * @property base 文件起始偏移（SAF 的 `startOffset`，指向实际数据起点）
     */
    private class Source(
        val channel: java.nio.channels.ReadableByteChannel,
        val seekable: Boolean,
        val base: Long,
    )

    /**
     * 打开文件数据源。
     *
     * 优先走 `openAssetFileDescriptor`：本地文件能拿到可 seek 的 FileChannel，
     * 断点续传时可直接跳到缺失分片（零 IO）；
     * 云端文档等不支持 seek 的来源则退回普通输入流（顺序读完，已传分片只读不传）。
     */
    private fun openSource(file: PendingFile): Source {
        val resolver = context?.contentResolver

        if (resolver != null) {
            // 1) 可 seek 路径：AssetFileDescriptor 提供 startOffset 与文件描述符
            val afd = runCatching { resolver.openAssetFileDescriptor(file.uri, "r") }.getOrNull()
            if (afd != null) {
                try {
                    val fis = java.io.FileInputStream(afd.fileDescriptor)
                    val ch = fis.channel
                    val seekable = runCatching { ch.position(afd.startOffset); true }.getOrDefault(false)
                    return Source(ch, seekable, afd.startOffset)
                } catch (_: Exception) {
                    // 落到普通流路径
                }
            }
            // 2) 普通内容流
            runCatching { resolver.openInputStream(file.uri) }.getOrNull()?.let {
                return Source(java.nio.channels.Channels.newChannel(it), seekable = false, base = 0)
            }
        }

        val ctx = context ?: throw IllegalStateException("需要 Context 才能读取文件: ${file.name}")
        val fis = runCatching { ctx.contentResolver.openInputStream(file.uri) }.getOrNull()
            ?: throw IllegalStateException("无法读取文件: ${file.name}")
        return Source(java.nio.channels.Channels.newChannel(fis), seekable = false, base = 0)
    }

    /**
     * 顺序读取器：按偏移顺序读文件，逐片算哈希并投递到队列。
     *
     * - 可 seek 的来源：直接跳到分片偏移读取，已传分片完全不读（续传零 IO）。
     * - 不可 seek 的来源：顺序读完整个流，已传分片只读不传（保持整文件哈希正确）。
     */
    private suspend fun produceChunks(
        source: Source,
        file: PendingFile,
        chunkSize: Int,
        chunkCount: Int,
        skip: BitSet,
        whole: MessageDigest,
        hashComplete: AtomicLong,
        emit: suspend (Chunk) -> Unit,
    ) {
        val seekChannel = source.channel as? java.nio.channels.SeekableByteChannel
        source.channel.use { ch ->
            val total = file.size
            var index = 0
            var remaining = total
            while (remaining > 0 && (chunkCount <= 0 || index < chunkCount)) {
                val want = minOf(remaining, chunkSize.toLong()).toInt()
                val alreadyDone = skip.get(index)

                if (alreadyDone && source.seekable && seekChannel != null) {
                    // 已传且可 seek：直接跳过，不占用内存也不产生 IO
                    hashComplete.set(0L)
                    runCatching { seekChannel.position(source.base + (index + 1).toLong() * chunkSize) }
                    index++
                    remaining -= want
                    continue
                }

                // 读取分片前先占用全局槽位：保证驻留内存有界
                globalSlots.acquire()
                var released = false
                try {
                    if (source.seekable && seekChannel != null) {
                        runCatching { seekChannel.position(source.base + index.toLong() * chunkSize) }
                    }
                    val data = readExact(ch, want)
                    if (data.isEmpty()) {
                        globalSlots.release()
                        released = true
                        break
                    }
                    val sha = MessageDigest.getInstance("SHA-256").digest(data).toHex()
                    whole.update(data)
                    if (alreadyDone) {
                        // 已传分片：只读不传，但整文件哈希依然覆盖它
                        globalSlots.release()
                        released = true
                    } else {
                        emit(Chunk(index, index.toLong() * chunkSize, data, sha))
                    }
                } catch (e: Exception) {
                    if (!released) globalSlots.release()
                    throw e
                }
                index++
                remaining -= want
            }
        }
    }

    /** 精确读取 n 字节（不足则返回实际读到的长度）。 */
    private fun readExact(
        channel: java.nio.channels.ReadableByteChannel,
        n: Int,
    ): ByteArray {
        if (n <= 0) return ByteArray(0)
        val out = ByteArray(n)
        val buf = java.nio.ByteBuffer.wrap(out)
        var got = 0
        while (got < n) {
            val r = channel.read(buf)
            if (r < 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    /** 分块描述。 */
    private class Chunk(val index: Int, val offset: Long, val data: ByteArray, val sha: String) {
        val size: Int get() = data.size
    }

    /** 依据文件大小选择分块大小：小文件用细粒度保证进度观感，大文件用 4 MiB 降开销。 */
    private fun chooseChunkSize(size: Long): Int = when {
        size <= 0 -> HttpProto.LEGACY_HTTP_CHUNK_SIZE
        size <= 64L * 1024 * 1024 -> HttpProto.LEGACY_HTTP_CHUNK_SIZE
        else -> HttpProto.HTTP_CHUNK_SIZE
    }

    // ------------------------------------------------------------------
    // 协议交互
    // ------------------------------------------------------------------

    private data class InitResp(
        val session: String,
        val chunkSize: Int,
        val chunkCount: Int,
        val receivedB64: String,
        /** 服务端采用了与请求不同的分块大小（需要用新值重新 init）。 */
        val rechunked: Boolean,
    )

    /** 创建或恢复上传会话。 */
    private fun initSession(file: PendingFile, size: Long, chunkSize: Int, chunkCount: Int): InitResp {
        val conn = openConnection(HttpProto.PATH_UPLOAD_INIT, "POST")
        conn.setRequestProperty(HttpProto.HDR_NAME, URLEncoder.encode(file.name, "UTF-8"))
        conn.setRequestProperty(HttpProto.HDR_TOTAL_SIZE, size.toString())
        conn.setRequestProperty(HttpProto.HDR_CHUNK_COUNT, chunkCount.toString())
        conn.setRequestProperty(HttpProto.HDR_CHUNK_SIZE, chunkSize.toString())
        conn.setRequestProperty(HttpProto.HDR_FILE_ID, file.fileId)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        conn.outputStream.use { it.flush() }

        val code = conn.responseCode
        val text = conn.readBody()
        conn.disconnect()
        if (code != 200) throw IllegalStateException("init failed: HTTP $code $text")
        val json = org.json.JSONObject(text)
        val serverChunkSize = json.optInt(HttpProto.RESP_CHUNK_SIZE, chunkSize)
        val serverChunkCount = json.optInt(
            HttpProto.RESP_CHUNK_COUNT,
            if (size <= 0) 0 else ((size + serverChunkSize - 1) / serverChunkSize).toInt(),
        )
        return InitResp(
            session = json.getString("session"),
            chunkSize = serverChunkSize,
            chunkCount = serverChunkCount,
            receivedB64 = json.optString(HttpProto.RESP_RECEIVED_B64, ""),
            rechunked = serverChunkSize != chunkSize,
        )
    }

    /** 上传单个分块（失败重试 3 次）。 */
    private suspend fun uploadChunkWithRetry(session: String, chunk: Chunk) {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                uploadChunk(session, chunk)
                return
            } catch (e: Exception) {
                last = e
                delay(150L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("chunk ${chunk.index} failed")
    }

    /** 上传单个分块。 */
    private fun uploadChunk(session: String, chunk: Chunk) {
        val conn = openConnection(HttpProto.PATH_UPLOAD_CHUNK, "POST")
        conn.setRequestProperty(HttpProto.HDR_SESSION, session)
        conn.setRequestProperty(HttpProto.HDR_INDEX, chunk.index.toString())
        conn.setRequestProperty(HttpProto.HDR_SIZE, chunk.size.toString())
        conn.setRequestProperty(HttpProto.HDR_OFFSET, chunk.offset.toString())
        conn.setRequestProperty(HttpProto.HDR_CHUNK_SHA, chunk.sha)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(chunk.size)

        conn.outputStream.use { out ->
            out.write(chunk.data)
            out.flush()
        }

        val code = conn.responseCode
        val text = conn.readBody()
        conn.disconnect()
        if (code != 200) throw IllegalStateException("chunk ${chunk.index} failed: HTTP $code $text")
    }

    /** 提交合并，返回服务端计算出的整文件 SHA-256。 */
    private fun commitSession(session: String, fileSha: String): String {
        val conn = openConnection(HttpProto.PATH_UPLOAD_COMMIT, "POST")
        conn.setRequestProperty(HttpProto.HDR_COMMIT_SESSION, session)
        if (fileSha.isNotEmpty()) conn.setRequestProperty(HttpProto.HDR_FILE_SHA, fileSha)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        conn.outputStream.use { it.flush() }

        val code = conn.responseCode
        val text = conn.readBody()
        val sha = conn.getHeaderField(HttpProto.HDR_RESP_FILE_SHA).orEmpty()
        conn.disconnect()
        if (code != 200) throw IllegalStateException("commit failed: HTTP $code $text")
        return sha
    }

    /** 打开连接并设置超时。 */
    private fun openConnection(path: String, method: String): HttpURLConnection {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.useCaches = false
        return conn
    }

    /** 读取响应体（成功取 inputStream，失败取 errorStream）。 */
    private fun HttpURLConnection.readBody(): String {
        val stream = try {
            if (responseCode in 200..299) inputStream else errorStream
        } catch (_: Exception) {
            null
        }
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    /** Base64 位图解码（与 Java BitSet.toByteArray 字节序一致）。 */
    private fun decodeBitmap(b64: String): BitSet {
        if (b64.isEmpty()) return BitSet()
        return try {
            BitSet.valueOf(Base64Codec.decode(b64))
        } catch (_: Exception) {
            BitSet()
        }
    }
}

/** 字节数组转十六进制小写串。 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
