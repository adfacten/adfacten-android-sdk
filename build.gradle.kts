plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

// Shared SDK coordinates. Bump sdkVersion for every release; it is also compiled into the SDK (BuildConfig)
// and sent in the User-Agent / X-Adfacten-SDK header.
extra["sdkGroup"] = "com.adfacten"
extra["sdkVersion"] = "1.0.0"
extra["mavenRepoDir"] = layout.buildDirectory.dir("maven-repo").get().asFile
