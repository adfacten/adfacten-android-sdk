// Shared Maven publication for the SDK modules. `./gradlew publish` writes a static Maven layout to
// build/maven-repo, which is uploaded as-is to https://cdn.adfacten.com/maven/.
apply(plugin = "maven-publish")

val pomName = if (project.extra.has("pomName")) project.extra["pomName"] as String else "Adfacten Ads SDK"
val pomDescription = if (project.extra.has("pomDescription")) project.extra["pomDescription"] as String
    else "Adfacten Ads SDK for Android: banner, interstitial, rewarded, native and app-open ads"
val repoDir = rootProject.extra["mavenRepoDir"] as File

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = rootProject.extra["sdkGroup"] as String
                artifactId = project.name
                version = rootProject.extra["sdkVersion"] as String
                pom {
                    name.set(pomName)
                    description.set(pomDescription)
                    url.set("https://github.com/adfacten/adfacten-android-sdk")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    scm {
                        url.set("https://github.com/adfacten/adfacten-android-sdk")
                        connection.set("scm:git:https://github.com/adfacten/adfacten-android-sdk.git")
                    }
                    organization {
                        name.set("Adfacten")
                        url.set("https://adfacten.com/")
                    }
                    developers {
                        developer {
                            id.set("adfacten")
                            name.set("Adfacten")
                            email.set("destek@adfacten.com")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "local"
                url = repoDir.toURI()
            }
        }
    }
}
