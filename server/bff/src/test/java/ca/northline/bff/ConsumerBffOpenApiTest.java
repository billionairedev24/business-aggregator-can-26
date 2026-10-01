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
 * S-125: the consumer-bff's session API (the {@code consumer} profile) equals docs/api/openapi/bff-consumer-internal.yaml.
 * Its own context on purpose: generating the document inside {@link ConsumerBffTest}'s context made that context's
 * relay calls hit a JDK HttpClient race (NPE in {@code Http1Exchange.requestMoreBody}) — see DECISIONS, S-125.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "consumer"})
class ConsumerBffOpenApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void committedSpecMatchesTheCode() throws Exception {
        OpenApiSnapshot.verify(mvc, "/bff/v3/api-docs", "bff-consumer", List.of("internal"));
    }
}
