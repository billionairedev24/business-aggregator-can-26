package ca.northline.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * S-104: the public, unauthenticated endpoints read their bodies whole (a Stripe webhook's signed payload, a guest's
 * cart). A body over the platform cap (5 MB) is refused with 413 before anything reads it.
 */
class RequestSizeLimitApiTest extends IntegrationTest {

    @Test
    void anOversizedWebhook_is413_beforeTheSignatureIsEvenChecked() throws Exception {
        mvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=00")
                        .content(new byte[5 * 1024 * 1024 + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("payload_too_large"));
    }

    @Test
    void anOversizedGuestCart_is413() throws Exception {
        mvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Northline-Guest", "s104-guest-0000000000000000")
                        .content(new byte[6 * 1024 * 1024]))
                .andExpect(status().isPayloadTooLarge());
    }
}
