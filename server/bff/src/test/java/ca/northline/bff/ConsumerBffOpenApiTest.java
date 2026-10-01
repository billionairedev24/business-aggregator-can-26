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
 * Its own context, with the local cookie names the committed document shows ({@link ConsumerBffTest} uses the cloud
 * ones). It was split out in S-125 because of a relay race that S-135 fixed ({@link RelayRaceTest}).
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
