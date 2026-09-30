package ca.northline.auth.federation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.AuthIntegrationTest;
import org.junit.jupiter.api.Test;

/** Without client registrations (the test profile, a laptop) the buttons say "not available" instead of failing. */
class FederationUnavailableTest extends AuthIntegrationTest {

    @Test
    void anUnconfiguredProvider_goesBackToSignInWithAnExplanation() throws Exception {
        mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3100/sign-in?error=federation_unavailable"));
        mvc.perform(get("/oauth2/authorization/apple"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3100/sign-in?error=federation_unavailable"));
    }

    @Test
    void onTheConsumerSite_goesBackToItsSignIn() throws Exception {
        mvc.perform(get("/oauth2/authorization/apple").queryParam("app", "consumer"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/sign-in?error=federation_unavailable"));
    }
}
