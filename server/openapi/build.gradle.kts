// S-125: OpenAPI 3.1 documentation shared by the api, northline-auth and the BFF (docs/runbooks/api-docs.md).
// springdoc (Swagger UI and Scalar come from springdoc's own starters), the Redoc page (bundle from the npm registry,
// pinned and integrity-checked, served by the app itself: no CDN), the /docs landing page, the common security schemes
// and error schemas, and the narrow CSP for the viewer paths. A plain library — no boot jar. The test fixture
// OpenApiSnapshot is the drift check each app's OpenApiSpecsTest runs (and `make openapi` rewrites with).
import java.security.MessageDigest
import java.net.URI
import java.util.Base64

plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.springdoc.webmvc.ui)
    api(libs.springdoc.webmvc.scalar)
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    compileOnly("org.springframework.boot:spring-boot-starter-security") // the docs chain, where the app has security

    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testFixturesCompileOnly(libs.jspecify)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("bootJar") { enabled = false }
tasks.named("bootRun") { enabled = false }
tasks.named<Jar>("jar") { archiveClassifier.set("") }

// Redoc's standalone bundle from the npm registry (samop's pattern): pinned version + the registry's sha512 integrity,
// so a changed tarball fails the build. Checked with `npm view redoc@2.5.4 dist.integrity` on 2026-09-30.
val redocVersion = "2.5.4"
val redocIntegrity = "sha512-M6jWhG1qoBnH6TFmzJnstyCZ87HmOY/UzDm78mHiYihEdlV/YcS9ogOo1NlElnJMeLsyxHFe2yFc4sNjHTrABQ=="
val npmRegistry = providers.gradleProperty("npm.registry").orElse("https://registry.npmjs.org")
val redocTarball = layout.buildDirectory.file("npm/redoc-$redocVersion.tgz")

val downloadRedoc = tasks.register("downloadRedoc") {
    description = "Downloads redoc-$redocVersion.tgz from the npm registry and checks its integrity"
    val url = npmRegistry.map { "$it/redoc/-/redoc-$redocVersion.tgz" }
    val target = redocTarball
    val integrity = redocIntegrity
    inputs.property("url", url)
    inputs.property("integrity", integrity)
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        val bytes = URI.create(url.get()).toURL().openStream().use { it.readBytes() }
        val actual = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))
        check(actual == integrity) { "redoc-$redocVersion.tgz from ${url.get()} does not match its npm integrity ($actual)" }
        file.writeBytes(bytes)
    }
}

val redocBundle = tasks.register<Sync>("redocBundle") {
    description = "Unpacks Redoc's standalone bundle and licence into the classpath (northline-docs/redoc/)"
    dependsOn(downloadRedoc)
    from(redocTarball.map { tarTree(it) }) {
        include("package/bundles/redoc.standalone.js", "package/bundles/redoc.standalone.js.LICENSE.txt", "package/LICENSE")
        eachFile { path = "northline-docs/redoc/$name" }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/redoc"))
}
sourceSets.main { resources.srcDir(redocBundle) }
