plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.dependency.management) apply false
}
subprojects {
    apply(plugin = "java")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")
    group = "ca.northline"
    java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
    repositories { mavenCentral() }
    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.modulith:spring-modulith-bom:${rootProject.libs.versions.spring.modulith.get()}")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:${rootProject.libs.versions.spring.cloud.get()}")
        }
    }
    tasks.withType<Test> { useJUnitPlatform() }
}
