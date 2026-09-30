dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // S-14: HTTP health for Kubernetes probes (/actuator/health/liveness, /readiness on SERVER_PORT 8084); no other endpoints.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// S-25: the topic catalogue (deploy/kafka/topics.yaml) is packaged as classpath:kafka/topics.yaml, so the provisioning
// Job in the worker image and the tests read the same file as scripts/topics.sh and Terraform.
tasks.processResources {
    from(rootProject.file("../deploy/kafka/topics.yaml")) { into("kafka") }
}

tasks.withType<Test>().configureEach {
    // TopicCatalogueTest runs scripts/topics.sh --list and compares it with the Java derivation.
    systemProperty("northline.repo", rootProject.file("..").absolutePath)
}

// ./gradlew :worker:kafkaTopics --args='plan|verify|apply' — KAFKA_* from the environment or server/.env
// (docs/runbooks/infrastructure.md § 5.3, local.md).
tasks.register<JavaExec>("kafkaTopics") {
    group = "northline"
    description = "plan | verify | apply the Kafka topics of deploy/kafka/topics.yaml (never deletes)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ca.northline.worker.topics.TopicsCommand")
}
