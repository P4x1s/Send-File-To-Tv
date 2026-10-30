package com.lanfile.tv.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成工具（对齐 sunshine-send 的 QrCodeUtil）。
 *
 * 用于在电视屏幕上显示上传地址，手机扫码即可打开 Web 上传页，
 * 实现「扫码即传」的零配置体验。
 */
object QrCodeUtil {

    /**
     * 将文本内容编码为二维码 Bitmap。
     *
     * 逐像素填充生成纯色二维码，输出 RGB_565 以节省内存。
     * 编码失败返回 null，由调用方做判空兜底。
     *
     * @param content 要编码的文本（如 `http://192.168.1.23:9527`）
     * @param size 输出正方形边长（像素），默认 512
     * @param characterSet 内容编码，默认 UTF-8
     * @param errorCorrection 纠错级别（L/M/Q/H）
     * @param margin 二维码四周留白（模块数），默认 1
     * @param colorBlack 前景色（码点）
     * @param colorWhite 背景色
     * @return 二维码 Bitmap；失败时返回 null
     */
    fun createQRCodeBitmap(
        content: String,
        size: Int = 512,
        characterSet: String = "UTF-8",
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        margin: Int = 1,
        colorBlack: Int = Color.BLACK,
        colorWhite: Int = Color.WHITE
    ): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to characterSet,
                EncodeHintType.ERROR_CORRECTION to errorCorrection,
                EncodeHintType.MARGIN to margin
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) colorBlack else colorWhite)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
