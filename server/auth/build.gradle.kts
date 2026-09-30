dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client") // Google / Apple federation
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway") // only enabled under local/test (api owns prod migrations)
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation("org.springframework.security:spring-security-webauthn") // passkeys (brings webauthn4j-core 0.31.x)
    implementation(libs.totp)
    implementation(libs.ulid)
    // Token signing keys in a cloud KMS (S-7): only the provider selected by northline.kms.provider is instantiated.
    implementation(libs.aws.kms) { exclude(group = "software.amazon.awssdk", module = "netty-nio-client") }
    implementation(libs.gcp.kms)
    implementation(libs.azure.keyvault.keys) { exclude(group = "com.azure", module = "azure-core-http-netty") }
    implementation(libs.azure.identity) { exclude(group = "com.azure", module = "azure-core-http-netty") }
    implementation(libs.azure.core.http.jdk) // JDK HttpClient instead of Netty for the Azure SDK
    // SMS / voice codes (S-8): Twilio is plain REST (@HttpExchange); AWS End User Messaging SMS and voice uses the SDK.
    implementation(libs.aws.sms.voice) { exclude(group = "software.amazon.awssdk", module = "netty-nio-client") }
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.wiremock)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Same migration files as the api (db/migrations is the single source). Flyway runs them only under the `local` and
// `test` profiles so the auth server can start (and be tested) on its own; in prod the api owns migrations.
tasks.processResources {
    from(rootProject.file("../db/migrations")) { into("db/migration") }
}

// S-16: the dev seed is not a main resource (never in the boot jar or the image); only bootRun (`local` profile:
// classpath:db/seed-dev) and the tests see it — same as server/api.
val devSeedDir = layout.buildDirectory.dir("dev-seed")
val devSeedResources by tasks.registering(Sync::class) {
    from(rootProject.file("../db/seed-dev")) { into("db/seed-dev") }
    into(devSeedDir)
}
val devSeedClasspath = files(devSeedDir).builtBy(devSeedResources)
dependencies { testRuntimeOnly(devSeedClasspath) }

// Local signing keys (docs/runbooks/key-rotation.md): ./gradlew :auth:signingKeys --args='rotate'
tasks.register<JavaExec>("signingKeys") {
    group = "northline"
    description = "status | rotate [--immediately] | retire <kid> for the local signing key file (SIGNING_KEYS_DIR)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.auth.signing.SigningKeysCommand")
}

// OAuth clients (S-122, docs/runbooks/README.md § OAuth clients): ./gradlew :auth:oauthClients --args='list|sync'
// Profile: SPRING_PROFILES_ACTIVE, else `local`; database and client settings as for the server (env, server/.env).
tasks.register<JavaExec>("oauthClients") {
    group = "northline"
    description = "list | sync the OAuth clients configured under northline.oauth.clients into the database"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.auth.clients.OAuthClientsCommand")
    systemProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE") ?: "local")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // `./gradlew :auth:bootRun --args='--spring.profiles.active=local'`
    jvmArgs("-Xmx512m")
}
