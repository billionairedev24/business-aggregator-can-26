// Shared by api, auth, bff and worker: environment checks, the provider settings that pick cloud adapters
// (docs/runbooks/README.md) and the Kafka event envelope headers (EventHeaders). A plain library — no boot jar.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    // The Kafka event envelope (EventHeaders) shared by the api and northline-auth: @Externalized + the externalization
    // configuration. Compile-only: only the producers, which have Modulith's event externalization, call it.
    compileOnly("org.springframework.modulith:spring-modulith-events-api")
    testImplementation("org.springframework.modulith:spring-modulith-events-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("bootJar") { enabled = false }
tasks.named("bootRun") { enabled = false }
tasks.named<Jar>("jar") { archiveClassifier.set("") }
