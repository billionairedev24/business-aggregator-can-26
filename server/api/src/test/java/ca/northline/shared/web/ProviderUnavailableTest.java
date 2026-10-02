package ca.northline.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.PlaceNames;
import ca.northline.shared.ProviderUnavailable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** S-115: a provider outage is a 503 with {@code code}, {@code Retry-After} and the message in the caller's language. */
class ProviderUnavailableTest {

    static final String MESSAGE =
            "Payments are unavailable right now. Nothing was charged — try again in a few minutes.";

    @RestController
    static class Checkout {
        @PostMapping("/checkout")
        String place() {
            throw new ProviderUnavailable("payments_unavailable", MESSAGE, 60, new IllegalStateException("fake"));
        }
    }

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Checkout())
            .setControllerAdvice(
                    new ApiExceptionHandler(new StaticListableBeanFactory().getBeanProvider(PlaceNames.class)))
            .build();

    @Test
    void outage_is503_withRetryAfter_inEnglishAndFrench() throws Exception {
        mvc.perform(post("/checkout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.code").value("payments_unavailable"))
                .andExpect(jsonPath("$.detail").value(MESSAGE));
        mvc.perform(post("/checkout").header(HttpHeaders.ACCEPT_LANGUAGE, "fr-CA"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail")
                        .value("Les paiements sont indisponibles pour le moment. Rien n’a été débité — réessayez dans"
                                + " quelques minutes."));
    }
}
