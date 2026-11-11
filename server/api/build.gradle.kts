import java.util.Properties

// Developer tooling (Flyway CLI wrapper, category seeder). Separate source set so it never ships in the boot jar
// and is invisible to Spring Modulith's module scan.
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":email")) // transactional email (S-13)
    implementation(project(":sms")) // SMS team invitations (S-27)
    implementation(project(":search-index")) // S-44: the listings index contract (ListingDocument, languages)
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // S-163: the Elasticsearch client and its auto-configuration only — Spring Data Elasticsearch (repositories, mapping
    // context, a repository scan of every package at start-up) was never used. Loaded only with SEARCH_PROVIDER=elasticsearch
    // (ca.northline.platform.SearchProviderImportFilter).
    implementation("org.springframework.boot:spring-boot-elasticsearch")
    implementation("co.elastic.clients:elasticsearch-java")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation(libs.datasource.micrometer) // S-111: a span per SQL statement (no parameter values)
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    implementation("org.springframework.modulith:spring-modulith-events-jackson")
    implementation(project(":openapi")) // S-125: springdoc + Swagger UI + Scalar + Redoc, groups per audience
    implementation(libs.springdoc.webmvc.mcp) // S-127: MCP tools from the OpenAPI model (docs/runbooks/mcp.md)
    implementation(libs.spring.ai.mcp.server.webmvc) // S-127: the MCP server and its Streamable HTTP transport
    implementation("org.yaml:snakeyaml") // S-128: reads the committed OpenAPI documents (version from Spring Boot)
    implementation(libs.ulid)
    implementation(libs.stripe)
    implementation(libs.pdfbox) // S-41: tax documents as PDF (Apache-2.0; docs/DECISIONS.md)
    implementation(libs.maxmind.db) // S-192: the optional GeoLite2/GeoIP2 visitor detector (Apache-2.0; docs/DECISIONS.md)
    implementation(libs.lib.recur) // S-162: Plus renewals from an RFC 5545 RRULE (Apache-2.0; docs/DECISIONS.md)
    implementation(libs.mapstruct)
    // Object storage (S-10): only the provider selected by northline.storage.provider is instantiated.
    implementation(libs.aws.s3) { exclude(group = "software.amazon.awssdk", module = "netty-nio-client") }
    implementation(libs.gcp.storage)
    implementation(libs.azure.storage.blob) { exclude(group = "com.azure", module = "azure-core-http-netty") }
    implementation(libs.azure.identity) { exclude(group = "com.azure", module = "azure-core-http-netty") }
    implementation(libs.azure.core.http.jdk) // JDK HttpClient instead of Netty for the Azure SDK
    // Envelope encryption of stored secrets (S-32, ca.northline.shared.crypto): only the key service selected by
    // northline.kms.provider is instantiated, as in northline-auth (S-7).
    implementation(libs.aws.kms) { exclude(group = "software.amazon.awssdk", module = "netty-nio-client") }
    implementation(libs.gcp.kms)
    implementation(libs.azure.keyvault.keys) { exclude(group = "com.azure", module = "azure-core-http-netty") }
    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor(libs.lombok.mapstruct.binding)
    runtimeOnly("org.postgresql:postgresql")

    "toolsImplementation"("org.postgresql:postgresql")
    // S-194: the demo seed hashes the demo accounts' passwords as northline-auth does (Argon2id on BouncyCastle). Only
    // the tools carry it (/app/tools/lib in the image), never the api's own classpath.
    "toolsImplementation"(libs.bouncycastle.prov)
    "toolsCompileOnly"(libs.lombok)
    "toolsAnnotationProcessor"(libs.lombok)
    "toolsCompileOnly"(libs.jspecify)

    testImplementation(tools.output)
    testImplementation(libs.bouncycastle.prov) // S-194: DemoSeedTest runs the seed, which hashes with Argon2id
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(testFixtures(project(":openapi"))) // S-125: OpenApiSnapshot (spec drift check)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka") // S-26: the real wire format of externalized events
    testImplementation("org.testcontainers:testcontainers-elasticsearch") // S-44: the search API on Elasticsearch 9
    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":platform"))) // S-111: OtlpReceiver
    // S-72: bulk-import image URLs are fetched under the S-33 SSRF rules (platform EgressDnsResolver pins addresses)
    implementation("org.apache.httpcomponents.client5:httpclient5")
    testImplementation(libs.wiremock) // S-23: registry adapters against recorded HTTP stand-ins
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(listOf(
        "-Amapstruct.defaultComponentModel=spring",
        "-Amapstruct.defaultInjectionStrategy=constructor",
        "-Amapstruct.unmappedTargetPolicy=ERROR",
    ))
}

// Migrations live in /db (shared with ops tooling); package them on the classpath so bootRun, tests, the boot jar and
// the image all use `classpath:db/migration`.
tasks.processResources {
    from(rootProject.file("../db/migrations")) { into("db/migration") }
    from(rootProject.file("../db/seed")) { into("db/seed") }
    // Machine-readable rules (legal-details.schema.json, storefront-sections.json) are validated against at runtime.
    from(rootProject.file("../docs/spec")) { into("spec") }
    // S-128: the developer docs MCP server (/mcp/docs) reads the docs of the code it runs: Markdown and the committed
    // OpenAPI documents (S-125). Backlog CSVs and the machine-readable spec files are left out.
    from(rootProject.file("../docs")) {
        include("**/*.md", "api/openapi/*.yaml")
        exclude("backlog/**")
        into("northline-devdocs")
    }
}

// S-16: the dev seed (db/seed-dev, V1xx personas) is NOT a main resource, so it can't reach the boot jar or the image.
// It sits in its own directory that only local runs see: bootRun (`classpath:db/seed-dev` under the `local` profile),
// the tests and the Gradle DB tools (-Pdb.devSeed=true). DevSeedPackagingTest and DbToolTest keep it that way.
val devSeedDir = layout.buildDirectory.dir("dev-seed")
val devSeedResources by tasks.registering(Sync::class) {
    from(rootProject.file("../db/seed-dev")) { into("db/seed-dev") }
    into(devSeedDir)
}
val devSeedClasspath = files(devSeedDir).builtBy(devSeedResources)
dependencies { testRuntimeOnly(devSeedClasspath) }

// ./gradlew :api:flywayMigrate [-Pdb.url=jdbc:postgresql://localhost:5432/northline] [-Pdb.user=…] [-Pdb.password=…] [-Pdb.devSeed=true]
// ./gradlew :api:seedCategories  (same -Pdb.* properties)
// Each -Pdb.* falls back to DB_URL / DB_USER / DB_PASSWORD from the environment, then from server/.env, then the default.
val dotEnv = Properties().apply {
    val file = rootProject.file(".env")
    if (file.isFile) file.reader().use { load(it) }
}
fun dbSetting(property: String, env: String, default: String): String =
    providers.gradleProperty(property).orElse(providers.environmentVariable(env)).getOrElse(dotEnv.getProperty(env) ?: default)

fun JavaExec.dbTool(command: String) {
    group = "database"
    classpath = tools.runtimeClasspath + sourceSets.main.get().output + devSeedClasspath
    mainClass.set("ca.northline.tools.DbTool")
    dependsOn(tasks.named("processResources"), tasks.named("toolsClasses"))
    args(command)
    systemProperty("logback.configurationFile", file("src/tools/logback-tools.xml").absolutePath)
    systemProperty("db.url", dbSetting("db.url", "DB_URL", "jdbc:postgresql://localhost:5432/northline"))
    systemProperty("db.user", dbSetting("db.user", "DB_USER", "northline"))
    systemProperty("db.password", dbSetting("db.password", "DB_PASSWORD", "northline"))
    systemProperty("db.devSeed", providers.gradleProperty("db.devSeed").getOrElse("false"))
}
tasks.register<JavaExec>("flywayMigrate") {
    description = "Applies db/migrations (and db/seed-dev with -Pdb.devSeed=true) to -Pdb.url."
    dbTool("migrate")
}
tasks.register<JavaExec>("flywayInfo") {
    description = "Prints Flyway migration status for -Pdb.url."
    dbTool("info")
}
tasks.register<JavaExec>("seedCategories") {
    description = "Upserts db/seed/categories.json into catalogue.categories at -Pdb.url."
    dbTool("seed-categories")
}

// S-194: ./gradlew :api:demoSeed [-Pdemo.reset=true] — the one-click demo data against -Pdb.url (make demo-seed
// ENV=local runs it with NORTHLINE_DEMO_SEED=true and the demo accounts' credentials in the environment). Photos come
// straight from the photo library (web/packages/media/source); the image carries a copy in /app/tools/demo-photos.
tasks.register<JavaExec>("demoSeed") {
    group = "database"
    description = "Seeds the demo marketplace (S-194) into -Pdb.url; -Pdemo.reset=true wipes the demo rows first."
    classpath = tools.runtimeClasspath + sourceSets.main.get().output
    mainClass.set("ca.northline.tools.demo.DemoSeed")
    dependsOn(tasks.named("processResources"), tasks.named("toolsClasses"))
    args(if (providers.gradleProperty("demo.reset").getOrElse("false") == "true") "reset" else "seed")
    systemProperty("logback.configurationFile", file("src/tools/logback-tools.xml").absolutePath)
    environment("DB_URL", dbSetting("db.url", "DB_URL", "jdbc:postgresql://localhost:5432/northline"))
    environment("DB_USER", dbSetting("db.user", "DB_USER", "northline"))
    environment("DB_PASSWORD", dbSetting("db.password", "DB_PASSWORD", "northline"))
    environment("DEMO_PHOTOS_DIR", rootProject.file("../web/packages/media/source").absolutePath)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // `./gradlew :api:bootRun --args='--spring.profiles.active=local'`
    jvmArgs("-Xmx768m")
    classpath(devSeedClasspath) // the `local` profile applies classpath:db/seed-dev
}

// S-16: the migration Job runs DbTool from the api image. The tools classes and their logging configuration go to
// /app/tools — outside the application's classpath (/app/resources, /app/classes, /app/libs), so the api never scans
// them; the Job puts /app/tools first: java -cp /app/tools:/app/resources:/app/classes:/app/libs/* ca.northline.tools.DbTool migrate
val jibToolsDir = layout.buildDirectory.dir("jib-tools")
val jibTools by tasks.registering(Sync::class) {
    from(tools.output.classesDirs) { into("app/tools") }
    from(tools.output.resourcesDir!!) { into("app/tools") } // S-194: demo-seed/plan.json
    from("src/tools/logback-tools.xml") { into("app/tools") }
    // S-194: the demo seed's own libraries (BouncyCastle for Argon2id) and the photo library's demo and category photos
    // (web/packages/media/source, docs/runbooks/photos.md), which the seed uploads to the media store.
    from(configurations.named("toolsRuntimeClasspath").map { c -> c.filter { it.name.startsWith("bcprov") } }) { into("app/tools/lib") }
    from(rootProject.file("../web/packages/media/source")) {
        include("demo/**", "category/**")
        into("app/tools/demo-photos")
    }
    dependsOn(tasks.named("processToolsResources"))
    into(jibToolsDir)
}
extensions.configure<com.google.cloud.tools.jib.gradle.JibExtension> {
    extraDirectories {
        paths {
            path {
                setFrom(jibToolsDir.get().asFile)
                into = "/"
            }
        }
    }
}
tasks.matching { it.name.startsWith("jib") && it.name != "jibTools" }.configureEach { dependsOn(jibTools) }

// S-44: the search module pulls the Elasticsearch client's (large) type model into ArchUnit's / Modulith's class
// import, on top of every cached test context; 768m (the root default) ran out of heap at the end of the suite.
// S-130: the AI features' scripted-model context (ScriptedModelTest, one for all of them) tipped 1g at the end of
// the suite too.
// S-105: the privacy contributors' contexts tipped 1280m. Rather than raise the heap again, cap Spring's test context
// cache (default 32): least recently used contexts are closed, at the cost of rebuilding one if a later class needs it.
// S-157: 12 contexts ran out of heap just before SchemaOwnershipTests (the last, ArchUnit's full class import) once
// S-150–S-157 added theirs; 8 leaves that import its room without raising the heap the shared machines run it with.
tasks.named<Test>("test") {
    maxHeapSize = "1280m"
    systemProperty("spring.test.context.cache.maxSize", "8")
}
