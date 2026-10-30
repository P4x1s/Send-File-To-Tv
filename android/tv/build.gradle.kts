plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lanfile.tv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.lanfile.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    // TV 端文件管理列表
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 二维码（扫码即传）
    implementation("com.google.zxing:core:3.5.3")

    // 传输记录持久化
    implementation("com.google.code.gson:gson:2.11.0")

    // 深色主题（Material 组件）
    implementation("com.google.android.material:material:1.11.0")
}
