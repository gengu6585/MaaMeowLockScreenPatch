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
        versionCode = 6
        versionName = "1.2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("androidx.annotation:annotation:1.7.1")
}
