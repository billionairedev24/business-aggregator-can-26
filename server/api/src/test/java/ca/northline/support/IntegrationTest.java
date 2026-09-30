package ca.northline.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for API integration tests: full application context, MockMvc, profile {@code test}, Flyway-migrated
 * PostGIS from {@link SharedPostgres}. Extend it, use {@link #mvc}, {@link #data} and {@link TestJwt}:
 *
 * <pre>{@code
 * class QuoteApiTest extends IntegrationTest {
 *     @Test void ownerSeesQuotes() throws Exception {
 *         var biz = data.business(MerchantRole.OWNER);
 *         mvc.perform(get("/api/v1/merchants/{id}/quotes", biz.merchantId()).with(TestJwt.member(biz.userId())))
 *            .andExpect(status().isOk());
 *     }
 * }
 * }</pre>
 *
 * Keep subclasses free of extra {@code @MockitoBean}s where possible — each distinct configuration starts a new
 * Spring context (the container is still shared).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestData.class, ShopFixtures.class, RecordingEmailSender.Config.class, RecordingSmsTransport.Config.class})
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = SharedPostgres.INSTANCE;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected TestData data;

    /** Consumer Shop sellers and listings (S-49…S-52). */
    @Autowired
    protected ShopFixtures shopFixtures;

    /** Emails the application sent (S-13), instead of a provider. */
    @Autowired
    protected RecordingEmailSender emails;

    /** Text messages the application sent (S-27 invitations), instead of a provider. */
    @Autowired
    protected RecordingSmsTransport texts;
}
