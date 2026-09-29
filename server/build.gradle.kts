import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.dependency.management) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.errorprone) apply false
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
