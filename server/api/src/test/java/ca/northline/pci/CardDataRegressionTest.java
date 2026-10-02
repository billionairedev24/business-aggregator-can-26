package ca.northline.pci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * S-110 regression: Stripe's test card pushed through a request body is refused (422 {@code card_data}), never
 * persisted anywhere in the database, and the deployed log format (ECS JSON) shows it only masked.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "LOG_FORMAT=ecs")
class CardDataRegressionTest extends IntegrationTest {

    static final String PAN = "4242 4242 4242 4242";
    static final String DIGITS = "4242424242424242";

    @Autowired
    JdbcClient jdbc;

    @Test
    void aCardNumberInABodyIsRefusedNeverStoredAndLoggedMasked(CapturedOutput output) throws Exception {
        var biz = data.business(MerchantRole.OWNER);

        mvc.perform(patch("/api/v1/merchants/{id}", biz.merchantId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Pay me with " + PAN + " exp 12/29\"}")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                .andExpect(jsonPath("$.errors[0].rule").value("card_data"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Card numbers can't be sent here. Enter card details only in the secure card form."));

        mvc.perform(get("/api/v1/merchants/{id}", biz.merchantId()).with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("4242"))));

        // Nowhere in any schema: every text, JSON, array and number column of the shared test database.
        assertThat(CardDataScanner.contents(
                        jdbc,
                        CardDataScanner.columns(jdbc),
                        v -> v.replaceAll("[ -]", "").contains(DIGITS)))
                .isEmpty();

        var line = output.getOut()
                .lines()
                .filter(l -> l.contains("Refused card data"))
                .reduce((_, last) -> last)
                .orElseThrow(() -> new AssertionError("no refusal logged"));
        assertThat(line).startsWith("{").contains("[CARD …4242]", "displayName").doesNotContain(PAN, DIGITS);
        assertThat(output.getAll()).doesNotContain(PAN).doesNotContain(DIGITS);
    }

    @Test
    void frenchCallersReadItInFrench() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(patch("/api/v1/merchants/{id}", biz.merchantId())
                        .header("Accept-Language", "fr-CA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"5555-5555-5555-4444\"}")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value(org.hamcrest.Matchers.startsWith("Les numéros de carte ne peuvent pas")));
    }

    @Test
    void anOrdinaryBodyStillWorks() throws Exception {
        var biz = data.business(MerchantRole.OWNER);
        mvc.perform(patch("/api/v1/merchants/{id}", biz.merchantId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Prairie Wrench 2026\"}")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Prairie Wrench 2026"));
    }

    @Test
    void stripeWebhooksKeepTheirRawBody() throws Exception {
        // Read as raw bytes for the signature check: the guard leaves it to the signature (400), not 422.
        mvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=00")
                        .content("{\"id\":\"evt_1\",\"data\":{\"object\":{\"note\":\"" + PAN + "\"}}}"))
                .andExpect(status().isBadRequest());
    }
}
