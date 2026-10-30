// 根工程：仅声明插件版本，具体配置在各子模块。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // JVM 验证模块用（kotlin-gradle-plugin 同时提供 android / jvm 两个插件 id，
    // 版本必须在此统一声明，否则子模块再写 version 会报 "already on the classpath"）
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
}
