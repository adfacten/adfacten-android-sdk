import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Put the app key and placement ids from your Adfacten panel (Apps › your app) in sample/dev-app.properties
// or pass them as -Padfacten.<key>=... A new app stays in test mode until it is reviewed, so it only gets
// test ads. Leave "endpoint" unset to use the production tracker.
val dev = Properties().apply {
    val f = file("dev-app.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String): String =
    (project.findProperty("adfacten.$key") as String?) ?: dev.getProperty(key) ?: default

android {
    namespace = "com.adfacten.sample"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.adfacten.sample"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "APP_KEY", "\"${cfg("appKey", "apk_YOUR_APP_KEY")}\"")
        buildConfigField("String", "ENDPOINT", "\"${cfg("endpoint", "")}\"")
        buildConfigField("String", "PLC_BANNER", "\"${cfg("banner", "plc_YOUR_BANNER")}\"")
        buildConfigField("String", "PLC_INTERSTITIAL", "\"${cfg("interstitial", "plc_YOUR_INTERSTITIAL")}\"")
        buildConfigField("String", "PLC_REWARDED", "\"${cfg("rewarded", "plc_YOUR_REWARDED")}\"")
        buildConfigField("String", "PLC_NATIVE", "\"${cfg("native", "plc_YOUR_NATIVE")}\"")
        buildConfigField("String", "PLC_APP_OPEN", "\"${cfg("appOpen", "plc_YOUR_APP_OPEN")}\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            // Exercise the SDK's consumer R8 rules.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

dependencies {
    implementation(project(":ads"))
    implementation(project(":ads-video"))
    implementation(project(":ads-gaid"))
}
