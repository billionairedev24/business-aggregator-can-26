// S-42: the Elasticsearch listings indices — the search read model (Postgres stays the source of truth). Shared by the
// worker (bootstrap Job, indexer S-43, reindex S-71) and the api (search API S-44): the versioned index layout of
// deploy/search (packaged as classpath:search/), the synonym sets and the index operations on the official
// Elasticsearch 9 Java client. A plain library — no boot jar.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("co.elastic.clients:elasticsearch-java") // version from the Spring Boot BOM (Elasticsearch 9)
    implementation("tools.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-elasticsearch")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// deploy/search is the one copy of the layout (index settings + mappings, analysis per language, synonyms); the
// images and the tests read the same files.
tasks.processResources {
    from(rootProject.file("../deploy/search")) { into("search") }
}

tasks.named("bootJar") { enabled = false }
tasks.named("bootRun") { enabled = false }
tasks.named<Jar>("jar") { archiveClassifier.set("") }
