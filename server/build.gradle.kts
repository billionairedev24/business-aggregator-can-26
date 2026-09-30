import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.dependency.management) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.errorprone) apply false
    alias(libs.plugins.jib) apply false
}

val catalog = libs

subprojects {
    apply(plugin = "java")
    apply(plugin = "checkstyle")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "net.ltgt.errorprone")

    group = "ca.northline"

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(catalog.versions.java.get().toInt())) }
    }
    repositories { mavenCentral() }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.modulith:spring-modulith-bom:${catalog.versions.spring.modulith.get()}")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:${catalog.versions.spring.cloud.get()}")
        }
    }

    dependencies {
        "compileOnly"(catalog.lombok)
        "annotationProcessor"(catalog.lombok)
        "testCompileOnly"(catalog.lombok)
        "testAnnotationProcessor"(catalog.lombok)
        "compileOnly"(catalog.jspecify)
        "testCompileOnly"(catalog.jspecify)
        "errorprone"(catalog.errorprone.core)
        "errorprone"(catalog.nullaway)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:-processing"))
        options.errorprone {
            disableWarningsInGeneratedCode.set(true)
            excludedPaths.set(".*/build/generated/.*")
            // NullAway: everything under ca.northline is non-null unless annotated @org.jspecify.annotations.Nullable.
            check("NullAway", if (name.contains("Test")) CheckSeverity.OFF else CheckSeverity.ERROR)
            option("NullAway:AnnotatedPackages", "ca.northline")
            option("NullAway:JSpecifyMode", "true")
            option("NullAway:TreatGeneratedAsUnannotated", "true")
            option("NullAway:ExcludedFieldAnnotations", "org.springframework.beans.factory.annotation.Autowired")
            // Too noisy for Spring-style code; revisit when the codebase settles.
            disable("StringSplitter", "MissingSummary", "JavaTimeDefaultTimeZone")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "768m"
        maxParallelForks = 1
        jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }

    extensions.configure<CheckstyleExtension> {
        toolVersion = catalog.versions.checkstyle.get()
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxWarnings = 0
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            palantirJavaFormat(catalog.versions.palantir.java.format.get())
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

// ---- Container images (S-14, docs/runbooks/deploy.md) ----------------------------------------------------------------
// Jib builds OCI images without a Dockerfile or a Docker daemon: distroless Java 25 base (pinned by digest), non-root
// uid 65532, exploded classpath (libs / resources / classes layers), reproducible (fixed file times and creation time),
// OCI labels. Registry-agnostic: the image is <image.registry>/<app>:<image.tag>, e.g.
//   ./gradlew jib -Pimage.registry=123456789012.dkr.ecr.ca-central-1.amazonaws.com/northline -Pimage.tag=$(git rev-parse --short HEAD)
//   ./gradlew jibDockerBuild                  # local Docker daemon: northline/<app>:dev
// Each -Pimage.* falls back to REGISTRY / IMAGE_TAG / IMAGE_PLATFORMS in the environment.
data class AppImage(val mainClass: String, val port: Int, val description: String)

val appImages = mapOf(
    "api" to AppImage("ca.northline.NorthlineApplication", 8080, "Northline api (modular monolith)"),
    "auth" to AppImage("ca.northline.auth.AuthServerApplication", 9000, "Northline authorization server (OIDC)"),
    "bff" to AppImage("ca.northline.bff.BffApplication", 8082, "Northline studio BFF"),
    "worker" to AppImage("ca.northline.worker.WorkerApplication", 8084, "Northline worker (Kafka consumers)"),
)
fun imageSetting(property: String, env: String, default: String): String =
    providers.gradleProperty(property).orElse(providers.environmentVariable(env)).getOrElse(default).ifBlank { default }
val imageRegistry = imageSetting("image.registry", "REGISTRY", "northline").trimEnd('/')
val imageTag = imageSetting("image.tag", "IMAGE_TAG", "dev")
val imagePlatforms = imageSetting("image.platforms", "IMAGE_PLATFORMS", "linux/amd64,linux/arm64")
val baseImage = imageSetting(
    "image.base", "JAVA_BASE_IMAGE",
    // gcr.io/distroless/java25-debian13:nonroot (multi-arch index), 2026-09-30
    "gcr.io/distroless/java25-debian13:nonroot@sha256:ca60da1345c0f17b6d019049e6749e15f10fd3c0da86dec938d2b4ec565d0629",
)
val gitRevision: String = providers.environmentVariable("GIT_SHA").orElse(
    providers.exec {
        commandLine("git", "rev-parse", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() },
).getOrElse("unknown").ifBlank { "unknown" }

configure(subprojects.filter { it.name in appImages }) {
    apply(plugin = "com.google.cloud.tools.jib")
    val app = appImages.getValue(name)
    extensions.configure<com.google.cloud.tools.jib.gradle.JibExtension> {
        from {
            image = baseImage
            platforms {
                imagePlatforms.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { p ->
                    platform {
                        os = p.substringBefore("/")
                        architecture = p.substringAfter("/")
                    }
                }
            }
        }
        to {
            image = "$imageRegistry/${project.name}:$imageTag"
        }
        container {
            mainClass = app.mainClass
            user = "65532:65532"
            ports = listOf(app.port.toString())
            format = com.google.cloud.tools.jib.api.buildplan.ImageFormat.OCI
            // Container-aware heap; the pod's memory limit sizes it. Extra flags: JAVA_TOOL_OPTIONS in the chart.
            jvmFlags = listOf("-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.security.egd=file:/dev/urandom")
            environment = mapOf("SERVER_PORT" to app.port.toString())
            labels.set(mapOf(
                "org.opencontainers.image.title" to "northline-${project.name}",
                "org.opencontainers.image.description" to app.description,
                "org.opencontainers.image.source" to "https://github.com/billionairedev24/business-aggregator-can-26",
                "org.opencontainers.image.revision" to gitRevision,
                "org.opencontainers.image.version" to imageTag,
                "org.opencontainers.image.vendor" to "Northline",
                "org.opencontainers.image.base.name" to baseImage.substringBefore("@"),
            ))
            // Reproducible: Jib's defaults (epoch file times and creation time) are kept on purpose.
        }
    }
}

// ---- OpenAPI specs (S-125, docs/runbooks/api-docs.md) ---------------------------------------------------------------
// Each app's OpenApiSpecsTest compares the specs it serves with the committed docs/api/openapi/*.yaml (part of
// `./gradlew build`); -Popenapi.write=true (make openapi) writes them instead.
configure(subprojects.filter { it.name in setOf("api", "auth", "bff") }) {
    tasks.withType<Test>().configureEach {
        systemProperty("northline.repo", rootProject.file("..").absolutePath)
        val write = providers.gradleProperty("openapi.write").getOrElse("false")
        systemProperty("openapi.write", write)
        inputs.property("openapi.write", write)
        inputs.dir(rootProject.file("../docs/api/openapi")).withPropertyName("committedSpecs").optional()
    }
}
