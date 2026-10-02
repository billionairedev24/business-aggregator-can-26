// Shared by api, auth, bff and worker: environment checks, the provider settings that pick cloud adapters
// (docs/runbooks/README.md), the Kafka event envelope headers (EventHeaders) and the observability defaults (S-111:
// tracing, metrics and their export over OTLP). A plain library — no boot jar.
plugins { `java-test-fixtures` }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    // The Kafka event envelope (EventHeaders) shared by the api and northline-auth: @Externalized + the externalization
    // configuration. Compile-only: only the producers, which have Modulith's event externalization, call it.
    compileOnly("org.springframework.modulith:spring-modulith-events-api")
    // S-111: every app brings spring-boot-starter-opentelemetry; the observability wiring here only compiles against it
    // (and against spring-web for the HTTP observation filter) and is skipped where a class is missing.
    compileOnly("org.springframework.boot:spring-boot-starter-opentelemetry")
    compileOnly("org.springframework:spring-web")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    // S-33/S-72: EgressDnsResolver is an HttpClient 5 resolver; the apps that make outbound calls bring the client.
    compileOnly("org.apache.httpcomponents.client5:httpclient5")
    // S-113: the outbox backlog gauges (OutboxBacklog) in the apps with a Modulith JDBC event registry (api, auth).
    compileOnly("org.springframework:spring-jdbc")
    compileOnly("io.micrometer:micrometer-core")
    testImplementation("org.springframework.modulith:spring-modulith-events-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    testImplementation("org.springframework:spring-web")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("io.micrometer:micrometer-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // S-111 test fixture shared by the apps' tests: an OTLP/HTTP receiver that decodes what the apps export.
    testFixturesImplementation(libs.otel.proto)
    testFixturesImplementation("org.assertj:assertj-core")
    testFixturesImplementation("io.micrometer:micrometer-observation") // S-112 RedactionCheck
    testFixturesImplementation("org.slf4j:slf4j-api")
    testFixturesCompileOnly(libs.jspecify)
    testImplementation(libs.otel.proto)
}

// S-40: the fr-CA wording of the API's validation messages (MessageCatalogue), shared by the api and northline-auth.
tasks.processResources {
    from(rootProject.file("../docs/spec/validation-messages.fr-CA.tsv")) { into("i18n") }
}

tasks.named("bootJar") { enabled = false }
tasks.named("bootRun") { enabled = false }
tasks.named<Jar>("jar") { archiveClassifier.set("") }
