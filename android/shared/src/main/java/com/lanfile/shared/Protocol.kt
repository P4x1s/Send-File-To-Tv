package com.lanfile.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * 与 Python 参考实现完全一致的线协议常量与消息构造/解析。
 * 因此 Android 手机端可与 Python 电视端（反之亦然）跨语言互操作。
 */
const val PROTO_VERSION = 1
const val CONTROL_PORT = 41235
const val DATA_PORT = 41236
const val CHUNK_SIZE = 4 * 1024 * 1024
const val DEFAULT_THREADS = 8
const val NSD_SERVICE_TYPE = "_lanfile._tcp."

/** 单个文件的分块描述（offset 用 Long 以支持 >2GB）。 */
data class ChunkSpec(val index: Int, val offset: Long, val size: Int)

/** 清单中的文件项（不含本地路径，不传给对端）。 */
data class FileManifest(
    val fileId: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val chunkCount: Int,
)

private fun jso(vararg kv: Pair<String, Any>): String =
    JSONObject().apply { for ((k, v) in kv) put(k, v) }.toString()

object Messages {
    fun hello(): String = jso("t" to "HELLO", "proto" to PROTO_VERSION, "device" to "phone")

    fun ready(sessionId: String, accept: Boolean, chunkSize: Int): String =
        jso("t" to "READY", "session_id" to sessionId, "accept" to accept, "chunk_size" to chunkSize)

    fun manifest(sessionId: String, files: List<FileManifest>): String {
        val arr = JSONArray()
        for (f in files) {
            arr.put(JSONObject().apply {
                put("file_id", f.fileId)
                put("name", f.name)
                put("size", f.size)
                put("sha256", f.sha256)
                put("chunk_count", f.chunkCount)
            })
        }
        return jso("t" to "MANIFEST", "session_id" to sessionId, "files" to arr)
    }

    fun manifestAck(sessionId: String, accepted: Boolean, count: Int): String =
        jso("t" to "MANIFEST_ACK", "session_id" to sessionId, "accepted" to accepted, "files" to count)

    /** states: fileId -> (name, received, total) */
    fun progress(sessionId: String, states: Map<String, Triple<String, Long, Long>>): String {
        // 注意：files 使用 dict（JSONObject）而非 JSONArray。
        // Python 参考实现按 dict 解析（states.items()）；若发数组，
        // 旧版客户端会因 AttributeError 使控制连接读取线程崩溃，导致 FILE_DONE 丢失。
        val obj = JSONObject()
        for ((fid, triple) in states) {
            obj.put(fid, JSONObject().apply {
                put("name", triple.first)
                put("received", triple.second)
                put("total", triple.third)
            })
        }
        return jso("t" to "PROGRESS", "session_id" to sessionId, "files" to obj)
    }

    fun fileDone(sessionId: String, fileId: String, ok: Boolean, sha256: String, error: String): String =
        jso("t" to "FILE_DONE", "session_id" to sessionId, "file_id" to fileId, "ok" to ok, "sha256" to sha256, "error" to error)

    fun sessionDone(sessionId: String, ok: Boolean, count: Int): String =
        jso("t" to "SESSION_DONE", "session_id" to sessionId, "ok" to ok, "files" to count)

    fun chunkHeader(sessionId: String, fileId: String, index: Int, offset: Long, size: Int, sha256: String): String =
        jso(
            "t" to "CHUNK", "session_id" to sessionId, "file_id" to fileId,
            "index" to index, "offset" to offset, "size" to size, "sha256" to sha256,
        )

    fun chunkOk(fileId: String, index: Int): String =
        jso("t" to "CHUNK_OK", "file_id" to fileId, "index" to index)

    fun chunkFail(fileId: String, index: Int, error: String): String =
        jso("t" to "CHUNK_FAIL", "file_id" to fileId, "index" to index, "error" to error)
}

fun typeOf(line: String?): String? = if (line == null) null else runCatching { JSONObject(line).getString("t") }.getOrNull()

fun strOf(line: String?, key: String): String? = if (line == null) null else runCatching { JSONObject(line).getString(key) }.getOrNull()
fun boolOf(line: String?, key: String): Boolean = if (line == null) false else runCatching { JSONObject(line).getBoolean(key) }.getOrElse { false }
fun longOf(line: String?, key: String): Long = if (line == null) 0L else runCatching { JSONObject(line).getLong(key) }.getOrElse { 0L }
fun intOf(line: String?, key: String): Int = if (line == null) 0 else runCatching { JSONObject(line).getInt(key) }.getOrElse { 0 }
