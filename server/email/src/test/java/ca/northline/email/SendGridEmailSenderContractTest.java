package ca.northline.email;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code EMAIL_PROVIDER=sendgrid} against a WireMock stand-in of {@code POST /v3/mail/send}. Fake API key. */
class SendGridEmailSenderContractTest {

    static final String SEND = "/v3/mail/send";
    static final String KEY = "SG.fake-key-for-tests";
    static WireMockServer sendgrid;
    EmailSender sender;

    @BeforeAll
    static void start() {
        sendgrid = new WireMockServer(wireMockConfig().dynamicPort());
        sendgrid.start();
    }

    @AfterAll
    static void stop() {
        sendgrid.stop();
    }

    @BeforeEach
    void wire() {
        sendgrid.resetAll();
        sender = TestEmails.sender(TestEmails.props(EmailProperties.Provider.SENDGRID, sendgrid.baseUrl(), KEY));
    }

    @Test
    void sendsTheV3Body_textFirst_trackingOff() {
        sendgrid.stubFor(post(SEND).willReturn(aResponse().withStatus(202).withHeader("X-Message-Id", "m-1")));

        sender.send(TestEmails.message());

        sendgrid.verify(postRequestedFor(urlEqualTo(SEND))
                .withHeader("Authorization", equalTo("Bearer " + KEY))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"personalizations": [{"to": [{"email": "sam@example.com", "name": "Sam Lée"}]}],
                         "from": {"email": "no-reply@mail.northline.test", "name": "Northline"},
                         "reply_to": {"email": "support@northline.test"},
                         "subject": "Rejoignez Prairie Wrench sur Northline",
                         "content": [{"type": "text/plain", "value": "Bonjour"},
                                     {"type": "text/html", "value": "<p>Bonjour</p>"}],
                         "headers": {"List-Unsubscribe": "<http://localhost:8080/api/v1/email/unsubscribe?t=abc>",
                                     "List-Unsubscribe-Post": "List-Unsubscribe=One-Click"},
                         "categories": ["team-invitation"],
                         "tracking_settings": {"click_tracking": {"enable": false}, "open_tracking": {"enable": false}}}
                        """)));
    }

    @Test
    void badRequest_isRejected_withoutRetry() {
        sendgrid.stubFor(
                post(SEND)
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"errors\":[{\"message\":\"Does not contain a valid address.\",\"field\":\"personalizations.0.to.0.email\"}]}")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.REJECTED);
        assertThat(failure.getMessage()).contains("400", "valid address");
        sendgrid.verify(1, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void unverifiedSender_isUnavailable() {
        sendgrid.stubFor(
                post(SEND)
                        .willReturn(
                                aResponse()
                                        .withStatus(403)
                                        .withBody(
                                                "{\"errors\":[{\"message\":\"The from address does not match a verified Sender Identity.\"}]}")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
    }

    @Test
    void serverError_isRetried_thenSucceeds() {
        sendgrid.stubFor(post(SEND)
                .inScenario("flaky")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        sendgrid.stubFor(post(SEND)
                .inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(202)));

        sender.send(TestEmails.message());

        sendgrid.verify(2, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void serverError_everyTime_isUnavailable_afterThreeTries() {
        sendgrid.stubFor(post(SEND).willReturn(aResponse().withStatus(500)));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
        sendgrid.verify(3, postRequestedFor(urlEqualTo(SEND)));
    }
}
