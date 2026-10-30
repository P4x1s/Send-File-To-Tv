package com.lanfile.shared

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * 带缓冲的套接字读写器。
 *
 * 关键点：readLine() 读取到 '\n' 后，会把换行符之后的“残留字节”保留在内部缓冲中，
 * 供随后的 readExact() 继续消费。这样当 TCP 把“JSON 控制行”和紧随其后的二进制数据
 * 合并在同一个包里送达时，二进制不会被丢弃（这是 Python 端最初踩过的坑）。
 */
class FramedSocket(private val socket: Socket) {
    private val out: OutputStream = socket.getOutputStream()
    private val `in`: InputStream = socket.getInputStream()
    private val buf = ByteArray(1 shl 16)
    private var bufLen = 0
    private var bufPos = 0

    /** 读取一行（不含换行符）；对端关闭且无数据返回 null。 */
    fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            var i = bufPos
            while (i < bufLen) {
                if (buf[i] == '\n'.code.toByte()) {
                    sb.append(String(buf, bufPos, i - bufPos, StandardCharsets.UTF_8))
                    bufPos = i + 1
                    return sb.toString()
                }
                i++
            }
            val remaining = bufLen - bufPos
            if (remaining > 0) sb.append(String(buf, bufPos, remaining, StandardCharsets.UTF_8))
            bufPos = 0
            bufLen = `in`.read(buf)
            if (bufLen <= 0) return if (sb.isEmpty()) null else sb.toString()
        }
    }

    /** 精确读取 n 字节（优先消费内部缓冲）。 */
    fun readExact(n: Int): ByteArray {
        val outBytes = ByteArray(n)
        var got = 0
        while (got < n) {
            if (bufPos < bufLen) {
                val take = minOf(n - got, bufLen - bufPos)
                System.arraycopy(buf, bufPos, outBytes, got, take)
                bufPos += take
                got += take
            } else {
                bufLen = `in`.read(buf)
                bufPos = 0
                if (bufLen <= 0) throw IOException("连接在对端传输分块中途断开")
            }
        }
        return outBytes
    }

    fun sendLine(line: String) {
        out.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    fun sendBytes(b: ByteArray) {
        out.write(b)
        out.flush()
    }

    /**
     * 优雅关闭发送方向：发送 FIN，让已写入 socket 缓冲的数据全部送达对端。
     *
     * 直接 close() 时，未被对端确认的数据会触发 TCP RST，
     * 对端可能因此读不到此前写入的 FILE_DONE / SESSION_DONE 消息。
     */
    fun shutdownOutput() {
        runCatching { socket.shutdownOutput() }
    }

    fun close() = runCatching { socket.close() }
}
