plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.callmebakugo.videostore"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    defaultConfig {
        applicationId = "org.callmebakugo.videostore"
        minSdk = 29
        targetSdk = 34
        versionCode = 1050000
        versionName = "0.5.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("VS_KEYSTORE") ?: "${System.getProperty("user.home")}/.local/share/videostore.keystore")
            storePassword = System.getenv("VS_KEYSTORE_PASS")
            keyAlias = System.getenv("VS_KEYSTORE_ALIAS") ?: "videostore"
            keyPassword = System.getenv("VS_KEY_PASS")
            enableV1Signing = false
            enableV2Signing = true
        }
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("release"); isDebuggable = false } }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
}

// Small host checks without adding a test framework or changing engine dependencies.
tasks.register<JavaExec>("testDownloadLogic") {
    dependsOn("compileDebugUnitTestKotlin")
    classpath = files(layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"), layout.buildDirectory.dir("tmp/kotlin-classes/debug")) +
        configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("org.callmebakugo.videostore.DownloadLogicTestKt")
}
