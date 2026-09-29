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
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Same migration files as the api (db/migrations is the single source). Flyway runs them only under the `local` and
// `test` profiles so the auth server can start (and be tested) on its own; in prod the api owns migrations.
tasks.processResources {
    from(rootProject.file("../db/migrations")) { into("db/migration") }
    from(rootProject.file("../db/seed-dev")) { into("db/seed-dev") }
}

// Local signing keys (docs/runbooks/key-rotation.md): ./gradlew :auth:signingKeys --args='rotate'
tasks.register<JavaExec>("signingKeys") {
    group = "northline"
    description = "status | rotate [--immediately] | retire <kid> for the local signing key file (SIGNING_KEYS_DIR)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.auth.signing.SigningKeysCommand")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // `./gradlew :auth:bootRun --args='--spring.profiles.active=local'`
    jvmArgs("-Xmx512m")
}
