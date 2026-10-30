package com.lanfile.tv.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 传输记录数据模型（一条记录 = 一个已接收文件）。
 *
 * @property name 文件名（同时是记录的唯一标识）
 * @property size 文件字节数
 * @property path 文件在磁盘上的绝对路径
 * @property time 接收完成时间戳（毫秒）
 */
data class SavedFile(
    val name: String,
    val size: Long,
    val path: String,
    val time: Long,
)

/**
 * 传输记录持久化仓库（对齐 sunshine-send 的 FileRepository 设计）。
 *
 * 采用「SharedPreferences + 目录扫描」双层存储：
 * 1. 主存储：SharedPreferences 以 JSON 保存 [SavedFile] 列表；
 * 2. 兜底：启动时扫描上传目录，SharedPreferences 丢失或损坏时按磁盘文件自动恢复。
 *
 * 保存时机：上传完成、记录删除。读取时机：仅 Activity 创建时一次。
 *
 * @param context 任意 Context（内部仅使用 application 级资源）
 */
class FileRepository(context: Context) {

    companion object {
        private const val TAG = "FileRepository"
        private const val PREFS_NAME = "lanfiletransfer"
        private const val KEY_FILES = "files"

        /**
         * 已从列表「清空」掉的文件名集合。
         *
         * 目录扫描兜底会把磁盘上的文件重新补录回列表，导致「清空」在下次
         * 刷新/重启后失效。用这个集合记住哪些文件被用户主动清空过：
         * **文件仍留在磁盘上**，只是不再出现在列表里。
         */
        private const val KEY_HIDDEN = "hidden_files"

        /** 上传文件存放的子目录名（位于 app 外部私有目录下）。 */
        const val UPLOAD_DIR_NAME = "Uploads"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 上传文件目录：/sdcard/Android/data/{pkg}/files/Uploads */
    val uploadDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, UPLOAD_DIR_NAME)
        .apply { if (!exists()) mkdirs() }

    /**
     * 加载全部有效记录（按时间**倒序**，即最新上传的文件排在最前面）。
     *
     * 流程：读 SharedPreferences -> 目录扫描补录缺失项 -> 过滤磁盘已删项 -> 回写一致化。
     * 已被「清空」的文件（[KEY_HIDDEN]）在此阶段被排除，不会重新出现在列表中。
     */
    fun load(): List<SavedFile> {
        val hidden = readHidden()
        val records = readPersistedRecords().filterNot { it.name in hidden }.toMutableList()
        var recovered = false

        for (file in uploadDir.listFiles() ?: emptyArray()) {
            if (!file.isFile || file.name.contains(".lanpart")) continue
            if (file.name in hidden) continue
            if (records.any { it.path == file.absolutePath }) continue
            Log.d(TAG, "Recover record from dir scan: ${file.name}")
            records.add(
                SavedFile(
                    name = file.name,
                    size = file.length(),
                    path = file.absolutePath,
                    time = file.lastModified(),
                )
            )
            recovered = true
        }

        val valid = records.filter { File(it.path).exists() }.sortedByDescending { it.time }
        if (recovered) saveAll(valid)
        return valid
    }

    /** 新增或更新一条记录（按文件名去重）。 */
    fun upsert(record: SavedFile) {
        val current = readPersistedRecords().filter { it.name != record.name }
        saveAll((current + record).sortedByDescending { it.time })
        // 重新收到同名文件（断点续传/再次上传）时，取消隐藏让它回到列表
        if (record.name in readHidden()) {
            writeHidden(readHidden() - record.name)
        }
    }

    /** 按文件名删除记录（不删除磁盘文件，文件删除由调用方负责）。 */
    fun remove(name: String) {
        saveAll(readPersistedRecords().filter { it.name != name })
    }

    /**
     * 清空列表：把当前所有记录标记为「已清空」，**但不删除磁盘文件**。
     *
     * 磁盘文件原地保留，只是不再出现在列表里（避免目录扫描兜底把它们补录回来）。
     * 用户之后重新上传同名文件会自动取消隐藏。
     *
     * @param names 要隐藏的文件名集合
     */
    fun hideAll(names: Collection<String>) {
        if (names.isEmpty()) return
        writeHidden(readHidden() + names)
        saveAll(readPersistedRecords().filterNot { it.name in names })
    }

    /** 读取「已清空」的文件名集合。 */
    private fun readHidden(): Set<String> {
        val json = prefs.getString(KEY_HIDDEN, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson(json, type) ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Parse hidden records failed", e)
            emptySet()
        }
    }

    /** 持久化「已清空」的文件名集合。 */
    private fun writeHidden(names: Set<String>) {
        prefs.edit().putString(KEY_HIDDEN, gson.toJson(names)).commit()
    }

    /** 读取持久化记录；解析失败返回空列表（由目录扫描兜底）。 */
    private fun readPersistedRecords(): List<SavedFile> {
        val json = prefs.getString(KEY_FILES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedFile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Parse persisted records failed, fallback to dir scan", e)
            emptyList()
        }
    }

    /** 同步落盘（commit 而非 apply，避免进程被杀时丢记录）。 */
    private fun saveAll(records: List<SavedFile>) {
        prefs.edit().putString(KEY_FILES, gson.toJson(records)).commit()
    }
}
