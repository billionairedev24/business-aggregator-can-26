dependencies {
    implementation(project(":platform"))
    implementation(project(":email")) // S-27: payout.failed email (templates, providers, once-per-recipient Mailer)
    implementation(project(":sms")) // S-27: SMS notifications (Twilio / AWS / log)
    implementation(project(":search-index")) // S-42: listings index layout, synonym sets, bootstrap
    implementation(libs.ulid)
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // S-33: partner webhook deliveries — a DnsResolver hook to pin the SSRF-checked address, no redirects, timeouts.
    implementation("org.apache.httpcomponents.client5:httpclient5")
    // S-14: HTTP health for Kubernetes probes (/actuator/health/liveness, /readiness on SERVER_PORT 8084); no other endpoints.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry") // S-111: traces, metrics, logs over OTLP
    implementation(libs.datasource.micrometer) // S-111: a span per SQL statement (no parameter values)
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus") // S-26: /actuator/prometheus (consumer lag, DLQ counts)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // The worker reads tables the api migrates; tests migrate a PostGIS container with the same db/migrations.
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-elasticsearch") // S-42: the search indices on Elasticsearch 9
    testImplementation(testFixtures(project(":platform"))) // S-111: OtlpReceiver
    testImplementation(libs.wiremock) // S-33: partner endpoints (signature, retries, auto-disable)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// S-25: the topic catalogue (deploy/kafka/topics.yaml) is packaged as classpath:kafka/topics.yaml, so the provisioning
// Job in the worker image and the tests read the same file as scripts/topics.sh and Terraform.
// S-26: the event JSON Schemas (server/api/src/main/resources/events) are packaged as classpath:events/, so the
// consumers validate every payload against the schema the api publishes with.
tasks.processResources {
    from(rootProject.file("../deploy/kafka/topics.yaml")) { into("kafka") }
    from(project(":api").file("src/main/resources/events")) { into("events") }
    // S-27: Settings › Notifications defaults, shared with the api (NotificationMatrixDefaultsSpecTest)
    from(rootProject.file("../docs/spec/notification-matrix-defaults.json")) { into("spec") }
    // S-33: the public webhook payloads (versioned, partner-facing); every delivery is validated against them.
    from(rootProject.file("../docs/spec/webhooks")) { into("webhooks") }
}

tasks.withType<Test>().configureEach {
    // TopicCatalogueTest runs scripts/topics.sh --list and compares it with the Java derivation.
    systemProperty("northline.repo", rootProject.file("..").absolutePath)
    inputs.dir(rootProject.file("../db/migrations"))
}

// ./gradlew :worker:kafkaTopics --args='plan|verify|apply' — KAFKA_* from the environment or server/.env
// (docs/runbooks/infrastructure.md § 5.3, local.md).
tasks.register<JavaExec>("kafkaTopics") {
    group = "northline"
    description = "plan | verify | apply the Kafka topics of deploy/kafka/topics.yaml (never deletes)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.worker.topics.TopicsCommand")
}

// ./gradlew :worker:searchIndices --args='plan|verify|apply' — ES_* from the environment or server/.env
// (docs/runbooks/search.md). S-42: synonym sets + the listings_en / listings_fr aliases and their versioned indices.
tasks.register<JavaExec>("searchIndices") {
    group = "northline"
    description = "plan | verify | apply the Elasticsearch listings indices and synonym sets of deploy/search"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.worker.search.SearchIndicesCommand")
}

// ./gradlew :worker:searchReindex [--args='--keep-old'] — DB_*, KAFKA_*, ES_* from the environment or server/.env
// (docs/runbooks/search.md § Reindex). S-71: new indices from Postgres, Kafka catch-up, alias swap, old ones deleted.
tasks.register<JavaExec>("searchReindex") {
    group = "northline"
    description = "rebuild listings_en / listings_fr from Postgres and swap the aliases (safe while live)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.worker.search.SearchReindexCommand")
}

// ./gradlew :worker:dlqReplay --args='list|replay --topic=<topic>.dlq --group=<consumer group> [filters] [--rate=20] --actor=… --reason=…'
// or --args='list|replay --deferred [filters] [--at=…] --actor=… --reason=…' (dead deferred notifications, S-115)
// (docs/runbooks/events.md § DLQ). KAFKA_* and DB_* from the environment or server/.env.
tasks.register<JavaExec>("dlqReplay") {
    group = "northline"
    description = "list | replay a consumer group's records from a .dlq topic to the original topic"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.worker.events.DlqReplayCommand")
}
