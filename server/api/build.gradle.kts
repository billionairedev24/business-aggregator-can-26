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
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    implementation("org.springframework.modulith:spring-modulith-events-jackson")
    implementation(project(":openapi")) // S-125: springdoc + Swagger UI + Scalar + Redoc, groups per audience
    implementation(libs.springdoc.webmvc.mcp) // S-127: MCP tools from the OpenAPI model (docs/runbooks/mcp.md)
    implementation(libs.spring.ai.mcp.server.webmvc) // S-127: the MCP server and its Streamable HTTP transport
    implementation(libs.ulid)
    implementation(libs.stripe)
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
    "toolsCompileOnly"(libs.lombok)
    "toolsAnnotationProcessor"(libs.lombok)
    "toolsCompileOnly"(libs.jspecify)

    testImplementation(tools.output)
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
    from("src/tools/logback-tools.xml") { into("app/tools") }
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
tasks.named<Test>("test") { maxHeapSize = "1g" }
