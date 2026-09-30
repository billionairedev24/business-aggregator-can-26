package ca.northline.email;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ca.northline.platform.EmailProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code EMAIL_PROVIDER=ses} against a WireMock stand-in of the SES API v2 ({@code POST /v2/email/outbound-emails},
 * REST-JSON, errors named by {@code x-amzn-ErrorType}), wired as the auto-configuration does. Fake credentials.
 */
class SesEmailSenderContractTest {

    static final String SEND = "/v2/email/outbound-emails";
    static WireMockServer ses;
    EmailSender sender;

    @BeforeAll
    static void start() {
        ses = new WireMockServer(wireMockConfig().dynamicPort());
        ses.start();
        System.setProperty("aws.accessKeyId", "test-access-key");
        System.setProperty("aws.secretAccessKey", "test-secret-key");
    }

    @AfterAll
    static void stop() {
        ses.stop();
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @BeforeEach
    void wire() {
        ses.resetAll();
        sender = TestEmails.sender(TestEmails.props(EmailProperties.Provider.SES, ses.baseUrl(), null));
    }

    @Test
    void sendsSimpleContent_withHeadersTagAndSigV4() {
        ses.stubFor(post(SEND).willReturn(json(200, "{\"MessageId\":\"0100-abc\"}")));

        sender.send(TestEmails.message());

        ses.verify(postRequestedFor(urlEqualTo(SEND))
                .withHeader("Authorization", containing("AWS4-HMAC-SHA256"))
                .withHeader("Authorization", containing("/ca-central-1/ses/aws4_request"))
                .withRequestBody(equalToJson("""
                        {"FromEmailAddress": "Northline <no-reply@mail.northline.test>",
                         "Destination": {"ToAddresses": ["${json-unit.any-string}"]},
                         "ReplyToAddresses": ["support@northline.test"],
                         "Content": {"Simple": {
                            "Subject": {"Data": "Rejoignez Prairie Wrench sur Northline", "Charset": "UTF-8"},
                            "Body": {"Text": {"Data": "Bonjour", "Charset": "UTF-8"},
                                     "Html": {"Data": "<p>Bonjour</p>", "Charset": "UTF-8"}},
                            "Headers": "${json-unit.ignore}"}},
                         "EmailTags": [{"Name": "template", "Value": "team-invitation"}]}
                        """)));
        var body = ses.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertThat(body)
                .contains("\"Name\":\"List-Unsubscribe-Post\",\"Value\":\"List-Unsubscribe=One-Click\"")
                .contains("=?UTF-8?") // the display name "Sam Lée" is MIME-encoded
                .contains("sam@example.com");
    }

    @Test
    void messageRejected_isRejected_withoutRetry() {
        ses.stubFor(post(SEND).willReturn(error(400, "MessageRejected", "Email address is not verified.")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.REJECTED);
        ses.verify(1, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void sendingPaused_isUnavailable() {
        ses.stubFor(post(SEND).willReturn(error(400, "SendingPausedException", "Sending is paused for this account.")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
        ses.verify(3, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void serverError_isRetried_thenSucceeds() {
        ses.stubFor(post(SEND)
                .inScenario("flaky")
                .whenScenarioStateIs(STARTED)
                .willReturn(error(500, "InternalFailure", "oops"))
                .willSetStateTo("recovered"));
        ses.stubFor(post(SEND)
                .inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(json(200, "{\"MessageId\":\"0100-def\"}")));

        sender.send(TestEmails.message());

        ses.verify(2, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void throttling_everyTime_isUnavailable_afterThreeTries() {
        ses.stubFor(post(SEND).willReturn(error(429, "TooManyRequestsException", "Maximum sending rate exceeded.")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
        assertThat(failure.getMessage()).contains("429");
        ses.verify(3, postRequestedFor(urlEqualTo(SEND)));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static ResponseDefinitionBuilder error(int status, String type, String message) {
        return json(status, "{\"message\":\"" + message + "\"}").withHeader("x-amzn-ErrorType", type);
    }
}
