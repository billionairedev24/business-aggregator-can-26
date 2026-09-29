// Developer tooling (Flyway CLI wrapper, category seeder). Separate source set so it never ships in the boot jar
// and is invisible to Spring Modulith's module scan.
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

dependencies {
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
    implementation(libs.springdoc.webmvc.ui)
    implementation(libs.ulid)
    implementation(libs.stripe)
    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)
    annotationProcessor(libs.lombok.mapstruct.binding)
    runtimeOnly("org.postgresql:postgresql")

    "toolsImplementation"("org.postgresql:postgresql")
    "toolsCompileOnly"(libs.lombok)
    "toolsAnnotationProcessor"(libs.lombok)
    "toolsCompileOnly"(libs.jspecify)

    testImplementation(tools.output)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.archunit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(listOf(
        "-Amapstruct.defaultComponentModel=spring",
        "-Amapstruct.defaultInjectionStrategy=constructor",
        "-Amapstruct.unmappedTargetPolicy=ERROR",
    ))
}

// Migrations live in /db (shared with ops tooling); package them on the classpath so bootRun, tests and the
// boot jar all use `classpath:db/migration` (+ `classpath:db/seed-dev` under the `local` profile).
tasks.processResources {
    from(rootProject.file("../db/migrations")) { into("db/migration") }
    from(rootProject.file("../db/seed-dev")) { into("db/seed-dev") }
    from(rootProject.file("../db/seed")) { into("db/seed") }
    // Machine-readable rules (legal-details.schema.json, storefront-sections.json) are validated against at runtime.
    from(rootProject.file("../docs/spec")) { into("spec") }
}

// ./gradlew :api:flywayMigrate [-Pdb.url=jdbc:postgresql://localhost:5432/northline] [-Pdb.user=…] [-Pdb.password=…] [-Pdb.devSeed=true]
// ./gradlew :api:seedCategories  (same -Pdb.* properties)
fun JavaExec.dbTool(command: String) {
    group = "database"
    classpath = tools.runtimeClasspath + sourceSets.main.get().output
    mainClass.set("ca.northline.tools.DbTool")
    dependsOn(tasks.named("processResources"), tasks.named("toolsClasses"))
    args(command)
    systemProperty("logback.configurationFile", file("src/tools/logback-tools.xml").absolutePath)
    systemProperty("db.url", providers.gradleProperty("db.url").getOrElse("jdbc:postgresql://localhost:5432/northline"))
    systemProperty("db.user", providers.gradleProperty("db.user").getOrElse("northline"))
    systemProperty("db.password", providers.gradleProperty("db.password").getOrElse("northline"))
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
}
