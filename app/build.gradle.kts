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
        versionCode = 19
        versionName = "1.2.15"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("androidx.annotation:annotation:1.7.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.nanohttpd:nanohttpd:2.3.1")
}
