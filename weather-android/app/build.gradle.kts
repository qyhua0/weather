/**
 * 微天气 Android 应用构建脚本（applicationId: top.modelx.weather）
 * @author zhenhua
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "top.modelx.weather"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.modelx.weather"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}
