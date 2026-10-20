package ca.northline.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.sms.twilio.TwilioSignature;
import ca.northline.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * S-157: with Twilio, every SMS webhook must carry Twilio's signature over the public URL Twilio was given (never the
 * request's own host): a forged or missing one is 403, a valid one is applied.
 */
@TestPropertySource(
        properties = {
            "northline.sms.provider=twilio",
            "northline.sms.account-id=ACtest00000000000000000000000000",
            "northline.sms.auth-token=test-token-not-real",
            "northline.sms.from=+15875550100",
            "northline.sms.webhook-base-url=https://api.northline.test"
        })
class SmsWebhookSignatureTest extends IntegrationTest {

    static final String URL = "https://api.northline.test/api/v1/webhooks/sms/inbound";

    @Test
    void onlyTwilioSignedDeliveriesAreApplied() throws Exception {
        var sid = "SM" + Ids.next();
        Map<String, List<String>> form =
                Map.of("MessageSid", List.of(sid), "From", List.of("+15875559998"), "Body", List.of("HELP"));
        var signature = TwilioSignature.sign("test-token-not-real", URL, form);

        mvc.perform(post("/api/v1/webhooks/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("MessageSid", sid)
                        .param("From", "+15875559998")
                        .param("Body", "HELP"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_signature"));
        mvc.perform(post("/api/v1/webhooks/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("MessageSid", sid)
                        .param("From", "+15875559998")
                        .param("Body", "STOP")) // the signed body said HELP
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/webhooks/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("MessageSid", sid)
                        .param("From", "+15875559998")
                        .param("Body", "HELP"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reply STOP to unsubscribe")));
    }
}
