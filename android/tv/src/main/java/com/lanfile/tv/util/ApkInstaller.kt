package com.lanfile.tv.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装辅助工具（对齐 sunshine-send 的 ApkInstaller）。
 *
 * 通过 FileProvider 暴露文件 URI 并拉起系统安装器，实现「上传 APK -> 自动弹安装流程」。
 */
object ApkInstaller {

    /** 判断文件名是否为 APK。 */
    fun isApk(fileName: String): Boolean =
        fileName.lowercase().endsWith(".apk")

    /**
     * 构造用于安装 APK 的 Intent。
     *
     * @param context 任意 Context
     * @param file APK 文件
     * @return 可交给 [androidx.activity.result.ActivityResultLauncher] 的 Intent
     * @throws IllegalArgumentException FileProvider 无法为该文件生成 URI 时抛出
     */
    fun buildInstallIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
