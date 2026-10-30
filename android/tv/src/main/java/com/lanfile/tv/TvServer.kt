package com.lanfile.tv

import android.util.Log
import com.lanfile.shared.CHUNK_SIZE
import com.lanfile.shared.CONTROL_PORT
import com.lanfile.shared.Crypto
import com.lanfile.shared.DATA_PORT
import com.lanfile.shared.FramedSocket
import com.lanfile.shared.Messages
import com.lanfile.shared.typeOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 电视端接收服务（与 Python 参考实现协议一致）：
 *  - 控制端口：HELLO -> READY -> MANIFEST -> MANIFEST_ACK，随后由通知线程推送 PROGRESS/SESSION_DONE。
 *  - 数据端口：并发接收分块，逐块 SHA256 校验后写入正确偏移；分块到齐后整文件校验并回 FILE_DONE。
 *  - 进度/状态通过 Listener 回调上抛给 UI。
 */
class TvServer(
    private val outDir: String,
    private val controlPort: Int = CONTROL_PORT,
    private val dataPort: Int = DATA_PORT,
    private val listener: Listener,
) {
    companion object {
        private const val TAG = "TvServer"

        /**
         * 追加一行诊断日志到文件（部分定制电视系统会屏蔽 logcat 应用日志，
         * 落盘是唯一可靠的现场取证手段）。
         */
        @Volatile
        private var diagFile: java.io.File? = null

        fun enableFileDiagnostics(dir: java.io.File) {
            runCatching {
                dir.mkdirs()
                diagFile = java.io.File(dir, "tvserver-diag.log")
            }
        }

        private fun diag(msg: String) {
            val f = diagFile ?: return
            runCatching {
                f.appendText("${System.currentTimeMillis()} $msg\n")
            }
        }

        /** 供同包内其他组件（如 UdpAnnouncer）写入诊断日志。 */
        fun diagPublic(msg: String) = diag(msg)
    }
    interface Listener {
        fun onSessionStarted(sessionId: String)
        fun onProgress(sessionId: String, states: Map<String, FileInfo>)
        fun onFileDone(sessionId: String, fileId: String, name: String, ok: Boolean, error: String)
        fun onSessionDone(sessionId: String, results: Map<String, FileResult>)
    }

    data class FileInfo(val name: String, val received: Long, val total: Long)
    data class FileResult(val name: String, val ok: Boolean, val error: String)

    private class FileState(
        var name: String, var size: Long, var sha256: String, var path: String, var totalChunks: Int,
    ) {
        var doneChunks = 0
        var received: Long = 0
        var done = false
        var ok = false
        var error = ""
    }

    private class Session(val id: String, val ctrl: FramedSocket, val sendLock: Any) {
        val files = ConcurrentHashMap<String, FileState>()
        var totalFiles = 0
        val doneFiles = AtomicInteger(0)
        val finished = AtomicBoolean(false)
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val seq = AtomicInteger(0)
    private var controlServer: ServerSocket? = null
    private var dataServer: ServerSocket? = null
    private val threads = mutableListOf<Thread>()
    @Volatile private var running = false

    fun start() {
        running = true
        controlServer = ServerSocket(controlPort)
        dataServer = ServerSocket(dataPort)
        threads += Thread { acceptLoop(controlServer) { handleControl(it) } }.also { it.start() }
        threads += Thread { acceptLoop(dataServer) { handleData(it) } }.also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { controlServer?.close() }
        runCatching { dataServer?.close() }
        threads.forEach { runCatching { it.interrupt() } }
    }

    private fun acceptLoop(ss: ServerSocket?, block: (Socket) -> Unit) {
        while (running) {
            try {
                val s = ss?.accept() ?: break
                Thread { runCatching { block(s) } }.start()
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun handleControl(sock: Socket) {
        val fc = FramedSocket(sock)
        val sessionId = "s%08d".format(seq.incrementAndGet())
        val session = Session(sessionId, fc, Any())
        sessions[sessionId] = session
        var tookOver = false
        try {
            val hello = fc.readLine() ?: return
            if (typeOf(hello) != "HELLO") return
            fc.sendLine(Messages.ready(sessionId, true, CHUNK_SIZE))
            listener.onSessionStarted(sessionId)
            val manifest = fc.readLine() ?: return
            if (typeOf(manifest) != "MANIFEST") return
            applyManifest(session, manifest)
            synchronized(session.sendLock) {
                fc.sendLine(Messages.manifestAck(sessionId, true, session.files.size))
            }
            diag("ctrl ready sid=$sessionId total=${session.totalFiles}")
            Thread { notifier(session) }.start()
            tookOver = true
        } catch (e: Exception) {
            diag("ctrl EXCEPTION sid=$sessionId ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "handleControl failed", e)
            if (!tookOver) runCatching { fc.close() }
        }
    }

    private fun applyManifest(session: Session, manifest: String) {
        val files = JSONObject(manifest).getJSONArray("files")
        session.totalFiles = files.length()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val fid = f.getString("file_id")
            val name = safeName(f.getString("name"))
            val size = f.getLong("size")
            val sha = f.getString("sha256")
            val count = f.getInt("chunk_count")
            val path = uniquePath(name)
            RandomAccessFile(path, "rw").use { if (size > 0) it.setLength(size) }
            session.files[fid] = FileState(name, size, sha, path, count)
        }
    }

    private fun handleData(sock: Socket) {
        val fc = FramedSocket(sock)
        try {
            val header = fc.readLine() ?: return
            if (typeOf(header) != "CHUNK") return
            val o = JSONObject(header)
            val sid = o.getString("session_id")
            val fid = o.getString("file_id")
            val index = o.getInt("index")
            val offset = o.getLong("offset")
            val size = o.getInt("size")
            val expect = o.getString("sha256")

            val session = waitSession(sid) ?: run {
                fc.sendLine(Messages.chunkFail(fid, index, "session not found"))
                return
            }
            val fs = session.files[fid] ?: run {
                fc.sendLine(Messages.chunkFail(fid, index, "unknown file"))
                return
            }
            val data = fc.readExact(size)
            if (Crypto.sha256(data) != expect) {
                diag("chunk hash mismatch fid=$fid idx=$index size=$size")
                fc.sendLine(Messages.chunkFail(fid, index, "chunk checksum mismatch"))
                return
            }
            RandomAccessFile(fs.path, "rw").use { raf ->
                raf.seek(offset)
                raf.write(data)
            }
            val completed: Boolean
            synchronized(fs) {
                fs.received += size
                fs.doneChunks += 1
                completed = fs.doneChunks >= fs.totalChunks
            }
            diag("chunk ok fid=$fid idx=$index size=$size done=${fs.doneChunks}/${fs.totalChunks} completed=$completed")
            fc.sendLine(Messages.chunkOk(fid, index))
            if (completed) finalizeFile(session, fid)
        } catch (e: Exception) {
            diag("handleData EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "handleData failed", e)
        } finally {
            fc.close()
        }
    }

    private fun finalizeFile(session: Session, fid: String) {
        val fs = session.files[fid] ?: return
        val whole = try {
            Crypto.sha256OfFile(fs.path)
        } catch (e: Exception) {
            Log.e(TAG, "sha256OfFile failed for $fid path=${fs.path}", e)
            ""
        }
        val ok = (whole.isNotEmpty() && whole == fs.sha256)
        val error = if (ok) "" else "整文件校验失败"
        fs.done = true
        fs.ok = ok
        fs.error = error

        // 关键顺序：必须先把 FILE_DONE 真正写入控制连接，再递增 doneFiles 计数。
        // 若先计数，notifier 线程可能据此判定「全部完成」而调用 finishSession 关闭
        // 控制连接，导致在途的 FILE_DONE 丢失（客户端表现为「未收到完成确认」）。
        val allDone: Boolean
            synchronized(session.sendLock) {
                try {
                    session.ctrl.sendLine(Messages.fileDone(session.id, fid, ok, whole, error))
                    diag("sent FILE_DONE fid=$fid ok=$ok")
                } catch (e: Exception) {
                    diag("send FILE_DONE EXCEPTION fid=$fid ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "send FILE_DONE failed fid=$fid", e)
                }
                val doneCount = session.doneFiles.incrementAndGet()
                allDone = doneCount >= session.totalFiles
                diag("doneFiles=$doneCount total=${session.totalFiles} allDone=$allDone")
            }
        listener.onFileDone(session.id, fid, fs.name, ok, error)
        if (allDone) finishSession(session)
    }

    private fun notifier(session: Session) {
        while (!session.finished.get()) {
            Thread.sleep(200)
            val tripleStates = session.files.mapValues { (_, fs) -> Triple(fs.name, fs.received, fs.size) }
            val infoStates = session.files.mapValues { (_, fs) -> FileInfo(fs.name, fs.received, fs.size) }
            synchronized(session.sendLock) {
                runCatching { session.ctrl.sendLine(Messages.progress(session.id, tripleStates)) }
            }
            listener.onProgress(session.id, infoStates)
            // 注意：此处不主动结束会话。
            // 会话结束统一由 finalizeFile 在「最后一个文件的 FILE_DONE 写出后」触发，
            // 否则 notifier 与 finalizeFile 之间存在竞态，可能在 FILE_DONE 尚未
            // 送达对端时就关闭控制连接，对端收到 RST 而读不到 FILE_DONE。
        }
    }

    private fun finishSession(session: Session) {
        if (!session.finished.compareAndSet(false, true)) return
        val results = session.files.mapValues { (_, fs) -> FileResult(fs.name, fs.ok, fs.error) }
        diag("finishSession sid=${session.id} files=${results.size} allOk=${results.values.all { it.ok }}")
        synchronized(session.sendLock) {
            try {
                session.ctrl.sendLine(Messages.sessionDone(session.id, results.values.all { it.ok }, results.size))
                diag("sent SESSION_DONE sid=${session.id}")
            } catch (e: Exception) {
                diag("send SESSION_DONE EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        listener.onSessionDone(session.id, results)
        // 优雅关闭：先 shutdownOutput（发送 FIN，让已写入的数据全部送达），
        // 再延迟关闭。若直接 close()，未确认的发送缓冲会触发 RST，
        // 导致对端在读取 FILE_DONE 时收到 "Connection reset" 而丢失回执。
        runCatching {
            session.ctrl.shutdownOutput()
            Thread.sleep(300)
            session.ctrl.close()
        }
        diag("ctrl closed sid=${session.id}")
        sessions.remove(session.id)
    }

    private fun waitSession(sid: String): Session? {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            sessions[sid]?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    private fun safeName(name: String): String {
        val base = File(name.replace('\\', '/')).name
        return base.ifBlank { "unnamed" }
    }

    private fun uniquePath(name: String): String {
        val dir = File(outDir).apply { mkdirs() }
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) {
                File(dir, "${name.substring(0, dot)}($i).${name.substring(dot + 1)}")
            } else {
                File(dir, "$name($i)")
            }
            i++
        }
        return candidate.absolutePath
    }
}
