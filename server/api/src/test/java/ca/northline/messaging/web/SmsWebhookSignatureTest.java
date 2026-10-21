package ca.northline.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.messaging.application.InboundSms;
import ca.northline.platform.SmsProperties;
import ca.northline.sms.twilio.TwilioSignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * S-157: with Twilio, every SMS webhook must carry Twilio's signature over the public URL Twilio was given (never the
 * request's own host): a forged, missing or body-tampered one is 403 and nothing is applied; a valid one is. No Spring
 * context (the controller over a recording use case), so the api suite's context cache isn't charged for it.
 */
class SmsWebhookSignatureTest {

    static final String TOKEN = "test-token-not-real";
    static final String URL = "https://api.northline.test/api/v1/webhooks/sms/inbound";

    final List<InboundSms.Received> received = new ArrayList<>();

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SmsWebhookController(
                    new SmsProperties(
                            SmsProperties.Provider.TWILIO,
                            "+15875550100",
                            null,
                            "ACtest00000000000000000000000000",
                            TOKEN,
                            null,
                            null),
                    new MockEnvironment(),
                    new InboundSms() {
                        @Override
                        public Optional<String> received(Received text) {
                            SmsWebhookSignatureTest.this.received.add(text);
                            return Optional.of("Reply STOP to unsubscribe");
                        }

                        @Override
                        public void status(Status status) {}
                    },
                    "https://api.northline.test/"))
            .build();

    @Test
    void onlyTwilioSignedDeliveriesAreApplied() throws Exception {
        Map<String, List<String>> form =
                Map.of("MessageSid", List.of("SM1"), "From", List.of("+15875559998"), "Body", List.of("HELP"));
        var signature = TwilioSignature.sign(TOKEN, URL, form);

        mvc.perform(post("/api/v1/webhooks/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("MessageSid", "SM1")
                        .param("From", "+15875559998")
                        .param("Body", "HELP"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_signature"));
        mvc.perform(post("/api/v1/webhooks/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("MessageSid", "SM1")
                        .param("From", "+15875559998")
                        .param("Body", "STOP")) // the signed body said HELP
                .andExpect(status().isForbidden());
        assertThat(received).isEmpty();
        mvc.perform(post("/api/v1/webhooks/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("MessageSid", "SM1")
                        .param("From", "+15875559998")
                        .param("Body", "HELP"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reply STOP to unsubscribe")));
        assertThat(received)
                .singleElement()
                .satisfies(r -> assertThat(r.provider()).isEqualTo("twilio"));
    }

    @Test
    void aProviderWithoutWebhooks_is503() throws Exception {
        var aws = MockMvcBuilders.standaloneSetup(new SmsWebhookController(
                        new SmsProperties(SmsProperties.Provider.AWS, "+15875550100", null, null, null, null, null),
                        new MockEnvironment(),
                        new InboundSms() {
                            @Override
                            public Optional<String> received(Received text) {
                                return Optional.empty();
                            }

                            @Override
                            public void status(Status status) {}
                        },
                        "https://api.northline.test"))
                .build();
        aws.perform(post("/api/v1/webhooks/sms/status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("MessageSid", "SM1")
                        .param("MessageStatus", "delivered"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("sms_webhooks_unconfigured"));
    }
}
