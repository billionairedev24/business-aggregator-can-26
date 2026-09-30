dependencies {
    implementation(project(":platform"))
    implementation(project(":email")) // S-27: payout.failed email (templates, providers, once-per-recipient Mailer)
    implementation(project(":sms")) // S-27: SMS notifications (Twilio / AWS / log)
    implementation(libs.ulid)
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // S-14: HTTP health for Kubernetes probes (/actuator/health/liveness, /readiness on SERVER_PORT 8084); no other endpoints.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
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

// ./gradlew :worker:dlqReplay --args='list|replay --topic=<topic>.dlq --group=<consumer group> [--event=<id>] [--force]'
// (docs/runbooks/events.md § DLQ). KAFKA_* and DB_* from the environment or server/.env.
tasks.register<JavaExec>("dlqReplay") {
    group = "northline"
    description = "list | replay a consumer group's records from a .dlq topic to the original topic"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.worker.events.DlqReplayCommand")
}
