package com.lanfile.shared

/** 将文件按 chunkSize 切分为分块列表（空文件返回单个 0 长度分块）。 */
fun planChunks(fileSize: Long, chunkSize: Int = CHUNK_SIZE): List<ChunkSpec> {
    if (fileSize <= 0) return listOf(ChunkSpec(0, 0, 0))
    val specs = ArrayList<ChunkSpec>()
    var off = 0L
    var idx = 0
    while (off < fileSize) {
        val size = minOf(chunkSize.toLong(), fileSize - off).toInt()
        specs.add(ChunkSpec(idx, off, size))
        off += size
        idx++
    }
    return specs
}
