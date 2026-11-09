plugins {
    alias(libs.plugins.android.library)
}

val sdkVersion = rootProject.extra["sdkVersion"] as String
val sdkGroup = rootProject.extra["sdkGroup"] as String

group = sdkGroup
version = sdkVersion

android {
    namespace = "com.adfacten.ads"
    compileSdk { version = release(37) }
    resourcePrefix = "adf_"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        // Styleables must carry the adf_ prefix, so they cannot match the view class names.
        disable += "CustomViewStyleable"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // Keep the metadata readable by apps that still build with Kotlin 2.1+.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        // Interface methods with bodies become Java default methods (listeners are easy to use from Java).
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

apply(from = rootProject.file("gradle/publish.gradle.kts"))
