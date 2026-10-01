package ca.northline.bff;

import ca.northline.openapi.OpenApiSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S-90: the console-bff's session API (the {@code console} profile) equals docs/api/openapi/bff-console-internal.yaml.
 * Local cookie names, as the committed document shows ({@link ConsoleBffTest} uses the cloud ones).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "console"})
class ConsoleBffOpenApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void committedSpecMatchesTheCode() throws Exception {
        OpenApiSnapshot.verify(mvc, "/bff/v3/api-docs", "bff-console", List.of("internal"));
    }
}
