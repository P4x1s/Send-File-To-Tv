// ============================================================
// 仓库配置
//   主源：google() + mavenCentral()
//   兜底：阿里云/腾讯云镜像（当代理与直连都不可达时仍能拉到依赖）
//   注意：镜像仅作 fallback，顺序在后，避免污染依赖解析。
// ============================================================

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
}

rootProject.name = "LanFileTransfer"

// 手机端（:phone）已停用：交付形态只有电视端 SFTT，用户改用手机浏览器
// 扫码打开 TV 内置网页上传页传文件（TV 端 res/raw/index.html 支持分块与断点续传）。
// 源码保留在 android/phone/ 下供参考，但不再参与构建，避免产出无人维护的 APK。
include(":shared", ":tv")

// JVM 验证模块：用真实源码在 JVM 上跑 TV 端 HTTP 服务器，供 Python 客户端实测。
// 不参与 assembleDebug（无 Android 依赖），仅在显式调用 :harness:run 时构建。
include(":harness")
