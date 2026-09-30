// S-34: the event JSON Schemas (server/api/src/main/resources/events) checked against the events the api and
// northline-auth publish, and against the base branch's copies. Not an app (no boot jar); runs as
//   ./gradlew :event-contracts:eventSchemas [-PeventSchemas.base=origin/main] [-PeventSchemas.requireBase=true]
// and, cheaply, inside `./gradlew build` through its tests (docs/runbooks/events.md § Schema checks).
dependencies {
    implementation(project(":platform")) // EventHeaders: the wire type and the producers' externalization
    implementation(project(":api")) // @Externalized events
    implementation(project(":auth")) // user.registered
    implementation(project(":worker")) // EventSchemas: the keywords the consumers implement, the validator
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.modulith:spring-modulith-events-api")
    implementation("tools.jackson.core:jackson-databind")
    implementation(libs.jspecify) // @Nullable at run time: nullable record components get a null sample too
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.apache.kafka:kafka-clients") // EnvelopeContractTest: records as the worker receives them
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("bootJar") { enabled = false }
tasks.named("bootRun") { enabled = false }

val repoRoot = rootProject.file("..").absolutePath
val base = providers.gradleProperty("eventSchemas.base").orElse(providers.environmentVariable("EVENT_SCHEMAS_BASE"))
val requireBase = providers.gradleProperty("eventSchemas.requireBase").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("northline.repo", repoRoot)
    base.orNull?.let { systemProperty("northline.eventSchemas.base", it) }
    inputs.dir(project(":api").file("src/main/resources/events"))
    // The base branch moves without any file here changing: never reuse an up-to-date result for check 4.
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("eventSchemas") {
    group = "verification"
    description = "S-34: schemas valid + supported keywords, every @Externalized event ↔ a schema, sample payloads " +
        "validate, no breaking change against the base branch without a version bump"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.contracts.EventContractCheck")
    args("--repo=$repoRoot", "--require-base=${requireBase.get()}")
    base.orNull?.let { args("--base=$it") }
}
