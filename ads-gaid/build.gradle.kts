plugins {
    alias(libs.plugins.android.library)
}

val sdkVersion = rootProject.extra["sdkVersion"] as String
val sdkGroup = rootProject.extra["sdkGroup"] as String
group = sdkGroup
version = sdkVersion
extra["pomName"] = "Adfacten Ads SDK — advertising id"
extra["pomDescription"] = "Optional: lets the Adfacten Ads SDK read the Google advertising id and App Set ID"

// No code: this artifact only brings in the two small Play Services libraries (and the AD_ID permission they
// declare). The core SDK detects them at runtime by reflection.
android {
    namespace = "com.adfacten.ads.gaid"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 21 }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":ads"))
    api(libs.play.ads.identifier)
    api(libs.play.appset)
}

apply(from = rootProject.file("gradle/publish.gradle.kts"))
