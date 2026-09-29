// Shared by api, auth, bff and worker: environment checks and the provider settings that pick cloud adapters
// (docs/runbooks/README.md). A plain library — no boot jar.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("bootJar") { enabled = false }
tasks.named("bootRun") { enabled = false }
tasks.named<Jar>("jar") { archiveClassifier.set("") }
