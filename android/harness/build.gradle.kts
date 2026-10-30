// ============================================================
// JVM 验证模块
// ------------------------------------------------------------
// 目的：在没有 Android 设备的情况下，**真实运行** TV 端 HTTP 上传服务器
//      （android/tv/.../http/HttpUploadServer.kt 的原样源码），
//      再用 Python 客户端（http_selftest.py）对其发真实 HTTP 请求，
//      从而验证 Kotlin 实现的协议正确性、并发分块上传与 SHA-256 校验。
//
// 做法：
//   - 直接引用 shared 与 tv 模块的 **源文件**（srcDirs），不是复制粘贴，
//     因此源码改动后验证结果始终反映真实实现；
//   - Android 内置的 org.json 在 JVM 上用 Maven 版 org.json:json 替代
//     （API 兼容，Android 的 org.json 就是它的同源实现）。
// ============================================================

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin {
            // 直接指向真实实现源码
            srcDir("../shared/src/main/java")
            srcDir("../tv/src/main/java/com/lanfile/tv/http")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    // Android 内置 org.json 的 JVM 等价实现
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("com.lanfile.tv.http.JvmHarnessKt")
}

tasks.named<JavaExec>("run") {
    // 把命令行参数透传给 main
    args = project.findProperty("harnessArgs")?.toString()?.split(" ") ?: emptyList()
    standardInput = System.`in`
}
