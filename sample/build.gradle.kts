import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The defaults are Adfacten's public sample app: it always receives test ads, which are never billed.
// To try your own app, put its app key and placement ids from the Adfacten panel (Apps › your app) in
// sample/dev-app.properties or pass them as -Padfacten.<key>=... Leave "endpoint" unset for production.
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
        buildConfigField("String", "APP_KEY", "\"${cfg("appKey", "apk_3cnu84fqX8ucMX9XPyeQYHrR")}\"")
        buildConfigField("String", "ENDPOINT", "\"${cfg("endpoint", "")}\"")
        buildConfigField("String", "PLC_BANNER", "\"${cfg("banner", "plc_034uZ7K1c31r4F5eyGyUpC")}\"")
        buildConfigField("String", "PLC_INTERSTITIAL", "\"${cfg("interstitial", "plc_034uZ7K1i1xnu0Ksljnmy4")}\"")
        buildConfigField("String", "PLC_REWARDED", "\"${cfg("rewarded", "plc_034uZ7K1nrWgf7S5ATCLrI")}\"")
        buildConfigField("String", "PLC_NATIVE", "\"${cfg("native", "plc_034uZ7K1o0SSewGeBCGY9P")}\"")
        buildConfigField("String", "PLC_APP_OPEN", "\"${cfg("appOpen", "plc_034uZ7K1o901BKzbEbWDjK")}\"")
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
