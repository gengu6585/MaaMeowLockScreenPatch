plugins {
    id("com.android.application")
}

android {
    namespace = "com.tinkerlab.maameowpatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tinkerlab.maameowpatch"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // LSPosed / Xposed API（来自 LSPosed 官方仓库，与 EdXposed/Xposed 兼容）
    compileOnly("de.robv.android.xposed:api:82")
    // Android 框架引用（仅编译期）
    compileOnly("androidx.annotation:annotation:1.7.1")
}
