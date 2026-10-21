package ca.northline.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** S-157: what checkouts may offer — public; no wallet without Stripe (the test profile runs the fake gateway). */
class PaymentOptionsApiTest extends IntegrationTest {

    @Test
    void cardsOnlyWithTheFakeGatewayAndOpenToGuests() throws Exception {
        mvc.perform(get("/api/v1/public/payments/options"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.provider").value("fake"))
                .andExpect(jsonPath("$.applePay").value(false))
                .andExpect(jsonPath("$.googlePay").value(false));
    }
}
