// Transactional email (S-13), shared by the api (after-commit module listeners) and the worker (notifications
// consumer, S-27): the EmailSender port, its provider adapters, the en/fr templates and the once-per-recipient Mailer.
// A plain library — no boot jar. Only the adapter selected by northline.email.provider is instantiated.
dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-mail") // local (Mailpit) and smtp: Jakarta Mail
    implementation("org.eclipse.angus:angus-mail") // SMTP reply codes (rejected vs unavailable)
    implementation("org.springframework.boot:spring-boot-starter-jackson") // SendGrid / Azure JSON bodies
    implementation("org.springframework:spring-web") // RestClient + @HttpExchange
    implementation("org.springframework:spring-jdbc") // de-duplication in events.processed_events
    implementation("org.thymeleaf:thymeleaf")
    implementation(libs.aws.sesv2) { exclude(group = "software.amazon.awssdk", module = "netty-nio-client") }
    implementation(libs.azure.identity) { exclude(group = "com.azure", module = "azure-core-http-netty") }
    implementation(libs.azure.core.http.jdk) // JDK HttpClient instead of Netty for the Azure SDK

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.wiremock)
    testImplementation(libs.greenmail.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("bootJar") { enabled = false }
tasks.named("bootRun") { enabled = false }
tasks.named<Jar>("jar") { archiveClassifier.set("") }
