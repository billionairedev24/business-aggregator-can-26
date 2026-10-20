package ca.northline.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Totp;
import ca.northline.auth.support.SharedPostgres;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S-150: the dev seed's personas sign in with the authenticator keys and backup codes of {@code
 * scripts/dev/personas.json} — what {@code make creds} prints. The seed is applied to a database of its own (in the
 * shared container), as the {@code local} profile does, so the other suites keep their unseeded one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeededPersonaSignInTest {

    private static final String DATABASE = "seeded_personas";
    private static final String PERSONAS = readPersonas();

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void seededDatabase(DynamicPropertyRegistry registry) {
        var pg = SharedPostgres.INSTANCE;
        try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + DATABASE);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        registry.add(
                "spring.datasource.url", () -> pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DATABASE + "$1"));
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed-dev");
        registry.add("spring.flyway.out-of-order", () -> "true");
    }

    private static String readPersonas() {
        try {
            return Files.readString(Path.of("../../scripts/dev/personas.json"));
        } catch (IOException e) {
            throw new IllegalStateException("scripts/dev/personas.json (the test runs from server/auth)", e);
        }
    }

    private static String field(String key, String name) {
        List<String> values = JsonPath.read(PERSONAS, "$.personas[?(@.key == '" + key + "')]." + name);
        return values.getFirst();
    }

    private ResultActions postJson(String path, MockHttpSession session, String json) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private MockHttpSession startedFor(String identifier) throws Exception {
        var session = new MockHttpSession();
        postJson("/api/auth/sign-in", session, "{\"identifier\":\"" + identifier + "\"}")
                .andExpect(status().isOk());
        return session;
    }

    private static String codeNow(String secret) {
        return Totp.codeAt(secret, Totp.step(Instant.now()));
    }

    @Test
    void amara_signsInWithTheSeededAuthenticatorKey() throws Exception {
        var session = startedFor(field("amara", "email"));
        postJson("/api/auth/sign-in/totp", session, "{\"code\":\"" + codeNow(field("amara", "totpSecret")) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(field("amara", "id")))
                .andExpect(jsonPath("$.acr").value("mfa"));
    }

    @Test
    void everyPersona_signsInWithTheCodeMakeCredsShows_byMobileToo() throws Exception {
        List<Map<String, Object>> all = JsonPath.read(PERSONAS, "$.personas[*]");
        for (var p : all) {
            if (p.get("key").equals("amara")) {
                continue; // the other test uses this step's code (replay protection)
            }
            var session = startedFor((String) p.get("phone"));
            postJson("/api/auth/sign-in/totp", session, "{\"code\":\"" + codeNow((String) p.get("totpSecret")) + "\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.id").value(p.get("id")));
        }
    }

    @Test
    void aSeededBackupCode_worksOnce() throws Exception {
        var code = field("sam", "backupCodePrefix") + "-00003";
        postJson("/api/auth/sign-in/backup-code", startedFor(field("sam", "email")), "{\"code\":\"" + code + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(field("sam", "id")));
        postJson("/api/auth/sign-in/backup-code", startedFor(field("sam", "email")), "{\"code\":\"" + code + "\"}")
                .andExpect(status().isUnprocessableContent());
    }
}
