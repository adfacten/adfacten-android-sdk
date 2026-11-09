plugins {
    alias(libs.plugins.android.library)
}

val sdkVersion = rootProject.extra["sdkVersion"] as String
val sdkGroup = rootProject.extra["sdkGroup"] as String
group = sdkGroup
version = sdkVersion
extra["pomName"] = "Adfacten Ads SDK — video"
extra["pomDescription"] = "Optional VAST video support (Media3 ExoPlayer) for the Adfacten Ads SDK"

android {
    namespace = "com.adfacten.ads.video"
    compileSdk { version = release(37) }
    defaultConfig {
        // Apps may compile against older SDKs than we do (Flutter and React Native default to 35/36).
        aarMetadata { minCompileSdk = 21 }
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
}

dependencies {
    api(project(":ads"))
    implementation(libs.media3.exoplayer)
}

apply(from = rootProject.file("gradle/publish.gradle.kts"))
