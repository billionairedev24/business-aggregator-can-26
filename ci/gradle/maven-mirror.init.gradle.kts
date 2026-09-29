// CI-only Gradle init script: resolve dependencies and plugins through a Maven mirror when MAVEN_MIRROR_URL is set.
// Maven Central answers bursts of CI traffic with HTTP 429; a mirror (Google's Central mirror, Artifactory, Nexus,
// GitLab/GitHub package proxy, …) avoids that. With the variable unset this script does nothing.
//
//   ./gradlew build --init-script ../ci/gradle/maven-mirror.init.gradle.kts
//
// Optional: MAVEN_MIRROR_USERNAME / MAVEN_MIRROR_PASSWORD for a mirror that needs credentials.
// Example value: https://maven-central.storage-download.googleapis.com/maven2/

val mirrorUrl = System.getenv("MAVEN_MIRROR_URL")?.trim()?.takeIf { it.isNotEmpty() }
val mirrorUser = System.getenv("MAVEN_MIRROR_USERNAME")?.takeIf { it.isNotEmpty() }
val mirrorPassword = System.getenv("MAVEN_MIRROR_PASSWORD")?.takeIf { it.isNotEmpty() }

fun RepositoryHandler.ciMirror() {
    maven {
        name = "ciMavenMirror"
        url = uri(mirrorUrl!!)
        if (mirrorUser != null && mirrorPassword != null) {
            credentials {
                username = mirrorUser
                password = mirrorPassword
            }
        }
    }
}

if (mirrorUrl != null) {
    logger.lifecycle("Maven mirror enabled: $mirrorUrl")

    // Plugins: the mirror first, then the default Gradle Plugin Portal.
    beforeSettings {
        pluginManagement.repositories {
            ciMirror()
            gradlePluginPortal()
        }
    }

    // Dependencies: this runs before each build script, so the mirror is consulted before mavenCentral().
    allprojects {
        repositories { ciMirror() }
    }
}
