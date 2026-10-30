package com.lanfile.tv.http

import com.lanfile.shared.HttpProto
import java.io.File

/**
 * JVM 验证用宿主程序。
 *
 * 它用**真实的** [HttpUploadServer] 实现（源码直接来自 tv 模块）启动服务，
 * 监听指定端口，然后把端口号打印到标准输出，供 Python 客户端连接测试。
 *
 * 用法：
 *   java -jar harness.jar <uploadDir> [port]
 *   端口传 0 则由系统分配空闲端口（实际端口打印在标准输出第一行）。
 */
fun main(args: Array<String>) {
    val uploadDir = File(args.getOrElse(0) { "harness_uploads" })
    val port = args.getOrElse(1) { "0" }.toInt()

    val server = HttpUploadServer(
        uploadDir = uploadDir,
        listener = object : HttpUploadServer.Listener {
            override fun onSessionStart(sessionId: String, fileName: String, totalBytes: Long) {
                println("EVENT session_start id=$sessionId name=$fileName total=$totalBytes")
            }

            override fun onProgress(sessionId: String, fileName: String, received: Long, total: Long) {
                println("EVENT progress id=$sessionId name=$fileName received=$received total=$total")
            }

            override fun onFileComplete(sessionId: String, fileName: String, file: File) {
                println("EVENT file_complete id=$sessionId name=$fileName size=${file.length()}")
            }

            override fun onError(sessionId: String, fileName: String, error: String) {
                println("EVENT error id=$sessionId name=$fileName error=$error")
            }

            override fun onFileDeleted(fileName: String) {
                println("EVENT file_deleted name=$fileName")
            }
        },
        // JVM 环境没有 res/raw，用一段等价的内联 HTML 代替（仅验证路由与响应）
        indexHtmlProvider = { "<html><body>LanFileTransfer upload page</body></html>" },
        port = port,
    )

    server.start()

    // 反射读取实际监听端口（port=0 时由系统分配）
    val actualPort = HttpUploadServer::class.java.getDeclaredField("serverSocket")
        .apply { isAccessible = true }
        .get(server)
        .let { it as java.net.ServerSocket }
        .localPort

    println("READY port=$actualPort dir=${uploadDir.absolutePath}")
    System.out.flush()

    // 保持进程存活，直到被终止
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
