package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.SharedPostgres;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * S-154: the verification email over real SMTP to Mailpit — the compose image, as {@code make up} runs it locally
 * (http://localhost:8025) — read back through Mailpit's API: bilingual subject with the code, the link to the app, the
 * CASL footer; the code from it creates the account. The rest of the suite records emails instead of sending them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext // its own context (real SMTP): closed afterwards so its connections don't crowd the shared database
class MailpitAccountEmailTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = SharedPostgres.INSTANCE;

    @SuppressWarnings("resource") // stopped by Testcontainers' reaper with the JVM
    static final GenericContainer<?> MAILPIT = startMailpit();

    private static GenericContainer<?> startMailpit() {
        var mailpit = new GenericContainer<>("axllent/mailpit:v1.29").withExposedPorts(1025, 8025);
        mailpit.start();
        return mailpit;
    }

    @DynamicPropertySource
    static void smtp(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        registry.add("northline.email.provider", () -> "local");
        registry.add("northline.email.smtp.host", MAILPIT::getHost);
        registry.add("northline.email.smtp.port", () -> MAILPIT.getMappedPort(1025));
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theVerificationEmail_reachesMailpit_inFrench_andItsCodeCreatesTheAccount() throws Exception {
        var address = "mailpit." + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@example.ca";
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/accounts")
                        .session(session)
                        .header("Accept-Language", "fr-CA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Élodie","lastName":"Tremblay","email":"%s","password":"une phrase de passe assez longue",
                                 "terms":true,"app":"studio"}""".formatted(address)))
                .andExpect(status().isAccepted());

        var message = awaitMessage(address);
        String subject = JsonPath.read(message, "$.Subject");
        String text = JsonPath.read(message, "$.Text");
        String html = JsonPath.read(message, "$.HTML");
        var code = Pattern.compile("(?m)^\\s*(\\d{6})\\s*$").matcher(text);
        assertThat(code.find()).isTrue();
        assertThat(subject).isEqualTo(code.group(1) + " est votre code pour confirmer votre courriel");
        assertThat(text)
                .contains("Confirmez votre courriel, Élodie")
                .contains("http://localhost:3100/verify-email?token=")
                .contains("Northline Marketplace Inc.");
        assertThat(html).contains(code.group(1)).contains("Confirmer mon courriel");
        List<Map<String, String>> to = JsonPath.read(message, "$.To");
        assertThat(to.getFirst().get("Address")).isEqualTo(address);

        mvc.perform(post("/api/auth/accounts/verify")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code.group(1) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(address));
    }

    /** Mailpit's API: search by recipient, then the message itself (the send is after commit, off the request). */
    private static String awaitMessage(String address) throws Exception {
        var http = HttpClient.newHttpClient();
        var api = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/";
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var search = http.send(
                            HttpRequest.newBuilder(URI.create(api + "search?query=to:" + address))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
            List<String> ids = JsonPath.read(search, "$.messages[*].ID");
            if (!ids.isEmpty()) {
                return http.send(
                                HttpRequest.newBuilder(URI.create(api + "message/" + ids.getFirst()))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .body();
            }
            java.util.concurrent.locks.LockSupport.parkNanos(200_000_000);
        }
        throw new AssertionError("No email to " + address + " in Mailpit");
    }
}
