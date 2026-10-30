package com.lanfile.phone

import com.lanfile.shared.CHUNK_SIZE
import com.lanfile.shared.Crypto
import com.lanfile.shared.DEFAULT_THREADS
import com.lanfile.shared.DATA_PORT
import com.lanfile.shared.FileManifest
import com.lanfile.shared.FramedSocket
import com.lanfile.shared.Messages
import com.lanfile.shared.boolOf
import com.lanfile.shared.planChunks
import com.lanfile.shared.strOf
import com.lanfile.shared.typeOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 待发送文件（含本地路径）。 */
data class FileSpec(val fileId: String, val name: String, val path: String)

/** 单文件传输状态（驱动 UI 进度）。 */
data class FileState(
    val name: String,
    val received: Long,
    val total: Long,
    val done: Boolean,
    val ok: Boolean,
    val error: String,
)

/**
 * 手机端传输客户端：
 *  - 维护一条控制连接（HELLO -> MANIFEST -> MANIFEST_ACK）。
 *  - 所有分块投入 Semaphore(threads) 并发的协程，每个分块独立数据连接发送。
 *  - 控制连接由独立协程读取 FILE_DONE / SESSION_DONE 更新最终状态。
 *  - 进度依据“已确认(ACK)字节数”在客户端累计，无需依赖电视端 PROGRESS 消息。
 */
class TransferClient(
    private val host: String,
    private val controlPort: Int,
    private val dataPort: Int = DATA_PORT,
    private val threads: Int = DEFAULT_THREADS,
) {
    private val _progress = MutableStateFlow<Map<String, FileState>>(emptyMap())
    val progress: StateFlow<Map<String, FileState>> = _progress.asStateFlow()

    private data class Prepared(
        val f: FileSpec,
        val chunks: List<com.lanfile.shared.ChunkSpec>,
        val chunkHashes: List<String>,
        val whole: String,
    )

    suspend fun transfer(files: List<FileSpec>): Boolean = withContext(Dispatchers.IO) {
        val control = Socket(host, controlPort)
        val fc = FramedSocket(control)
        try {
            fc.sendLine(Messages.hello())
            val ready = fc.readLine() ?: throw IOException("未收到 READY")
            val sid = strOf(ready, "session_id") ?: throw IOException("缺少 session_id")

            val prepared = files.map { f ->
                val size = File(f.path).length()
                val chunks = planChunks(size, CHUNK_SIZE)
                val (whole, chunkHashes) = Crypto.sha256OfFileWithChunks(f.path, chunks)
                Prepared(f, chunks, chunkHashes, whole)
            }
            val manifests = prepared.map {
                FileManifest(it.f.fileId, it.f.name, File(it.f.path).length(), it.whole, it.chunks.size)
            }
            fc.sendLine(Messages.manifest(sid, manifests))
            // 关键：MANIFEST_ACK 与后续的 PROGRESS/FILE_DONE/SESSION_DONE 共用同一条控制连接，
            // 若在 ACK 之前就启动读取协程，PROGRESS 可能被误当作 ACK。
            // 因此先循环读取直到拿到 MANIFEST_ACK，再启动读取协程处理后续消息。
            withTimeoutOrNull(10_000) {
                while (true) {
                    val line = fc.readLine() ?: throw IOException("未收到 MANIFEST_ACK")
                    if (typeOf(line) == "MANIFEST_ACK") break
                }
            } ?: throw IOException("未收到 MANIFEST_ACK")

            _progress.value = prepared.associate {
                it.f.fileId to FileState(it.f.name, 0, File(it.f.path).length(), false, false, "")
            }

            val allDone = CompletableDeferred<Boolean>()
            val reader = launch {
                try {
                    while (true) {
                        val line = fc.readLine() ?: break
                        when (typeOf(line)) {
                            "FILE_DONE" -> {
                                val fid = strOf(line, "file_id") ?: continue
                                val ok = boolOf(line, "ok")
                                val err = strOf(line, "error") ?: ""
                                _progress.value = _progress.value + (fid to
                                    _progress.value[fid]!!.copy(done = true, ok = ok, error = err, received = _progress.value[fid]!!.total))
                            }
                            "SESSION_DONE" -> {
                                allDone.complete(boolOf(line, "ok"))
                                // 关键：不能就此 break。
                                // 电视端并发发送各文件的 FILE_DONE，SESSION_DONE 可能在部分
                                // FILE_DONE 之前抵达控制连接；若此处直接退出循环，
                                // 剩余的 FILE_DONE 将永远不会被读取（表现为「未收到完成确认」）。
                                // 改为继续读到连接关闭，由对端关闭来终止循环。
                            }
                            else -> {} // 忽略 PROGRESS 等中间消息，继续读取 FILE_DONE / SESSION_DONE
                        }
                    }
                } catch (_: Exception) {
                    if (!allDone.isCompleted) allDone.complete(false)
                }
            }

            val sem = Semaphore(threads)
            val jobs = prepared.flatMap { p ->
                p.chunks.map { ch ->
                    async {
                        sem.withPermit { sendChunk(sid, p, ch) }
                    }
                }
            }
            jobs.awaitAll()

            // 等待会话结束。
            // 注意：不能只等 SESSION_DONE —— 电视端发出 SESSION_DONE 后即会关闭控制连接，
            // 尚未送达的 FILE_DONE 会随之丢失（表现为「未收到完成确认」）。
            // 必须确认每个文件的 FILE_DONE 都已收到。
            val ok = withTimeoutOrNull(30_000) {
                while (true) {
                    if (allDone.isCompleted) break
                    val states = _progress.value
                    if (states.isNotEmpty() && states.values.all { it.done }) break
                    delay(50)
                }
                allDone.await()
            } ?: false
            reader.cancel()
            ok
        } finally {
            fc.close()
        }
    }

    private suspend fun sendChunk(sid: String, p: Prepared, ch: com.lanfile.shared.ChunkSpec) {
        val chunkHash = p.chunkHashes[ch.index]
        repeat(5) { attempt ->
            try {
                val s = Socket(host, dataPort)
                val dfc = FramedSocket(s)
                try {
                    dfc.sendLine(Messages.chunkHeader(sid, p.f.fileId, ch.index, ch.offset, ch.size, chunkHash))
                    if (ch.size > 0) {
                        RandomAccessFile(p.f.path, "r").use { raf ->
                            raf.seek(ch.offset)
                            var left = ch.size
                            val buf = ByteArray(1 shl 20)
                            while (left > 0) {
                                val r = raf.read(buf, 0, minOf(buf.size, left))
                                if (r < 0) break
                                dfc.sendBytes(buf.copyOf(r))
                                left -= r
                            }
                        }
                    }
                    val res = dfc.readLine()
                    if (typeOf(res) == "CHUNK_OK") {
                        incReceived(p.f.fileId, ch.size.toLong())
                        return
                    }
                } finally {
                    dfc.close()
                }
            } catch (_: Exception) {
                // 重试
            }
            kotlinx.coroutines.delay(200L * (attempt + 1))
        }
        // 失败：标记该文件存在错误分块
        _progress.value = _progress.value.toMutableMap().apply {
            val cur = this[p.f.fileId]
            if (cur != null) this[p.f.fileId] = cur.copy(error = "分块#${ch.index} 发送失败")
        }
    }

    private fun incReceived(fileId: String, n: Long) {
        val cur = _progress.value[fileId] ?: return
        _progress.value = _progress.value + (fileId to cur.copy(received = cur.received + n))
    }
}
