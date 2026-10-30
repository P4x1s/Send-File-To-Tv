package com.lanfile.shared

import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

object Crypto {
    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    /** 流式计算整文件 SHA256（不整体载入内存）。 */
    fun sha256OfFile(path: String, chunkSize: Int = 1 shl 20): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { fis ->
            val buf = ByteArray(chunkSize)
            var n: Int
            while (fis.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    /**
     * 单次顺序读取同时得到整文件哈希与每个分块哈希（分块按 index 升序）。
     * 返回 (整文件sha256, List<分块sha256>)。
     */
    fun sha256OfFileWithChunks(path: String, chunks: List<ChunkSpec>, chunkSize: Int = 1 shl 20): Pair<String, List<String>> {
        val whole = MessageDigest.getInstance("SHA-256")
        val chunkHashes = ArrayList<String>(chunks.size)
        FileInputStream(path).use { fis ->
            val buf = ByteArray(chunkSize)
            for (ch in chunks) {
                val data = if (ch.size > 0) readExact(fis, buf, ch.size) else ByteArray(0)
                whole.update(data)
                chunkHashes.add(MessageDigest.getInstance("SHA-256").digest(data).toHex())
            }
        }
        return whole.digest().toHex() to chunkHashes
    }

    private fun readExact(fis: FileInputStream, buf: ByteArray, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = fis.read(buf, 0, minOf(buf.size, n - got))
            if (r < 0) throw IOException("文件读取越界")
            System.arraycopy(buf, 0, out, got, r)
            got += r
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
