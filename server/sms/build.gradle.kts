// SMS and voice (S-8 adapters, extracted in S-27 the way S-13 extracted server/email): the SmsTransport port, its
// provider adapters (Twilio REST, AWS End User Messaging SMS and voice, a logging fake) and their selection by
// northline.sms.provider. Used by northline-auth (verification codes), the api (SMS team invitations) and the worker
// (notifications). A plain library — no boot jar, no auto-configuration: apps import SmsTransportConfiguration.
dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jackson") // Twilio JSON answers
    implementation("org.springframework:spring-web") // RestClient + @HttpExchange
    implementation(libs.aws.sms.voice) { exclude(group = "software.amazon.awssdk", module = "netty-nio-client") }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.wiremock)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("bootJar") { enabled = false }
tasks.named("bootRun") { enabled = false }
tasks.named<Jar>("jar") { archiveClassifier.set("") }
