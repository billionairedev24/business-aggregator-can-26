package ca.northline.email;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ca.northline.platform.EmailProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code EMAIL_PROVIDER=azure} against a WireMock stand-in of Azure Communication Services {@code POST
 * /emails:send?api-version=2023-03-31}, with access-key (HMAC-SHA256) authentication. Fake key.
 */
class AzureEmailSenderContractTest {

    static final String SEND = "/emails:send?api-version=2023-03-31";
    static final byte[] KEY = "fake-acs-access-key-for-tests-only".getBytes(StandardCharsets.UTF_8);
    static WireMockServer acs;
    EmailSender sender;

    @BeforeAll
    static void start() {
        acs = new WireMockServer(wireMockConfig().dynamicPort());
        acs.start();
    }

    @AfterAll
    static void stop() {
        acs.stop();
    }

    @BeforeEach
    void wire() {
        acs.resetAll();
        var connectionString = "endpoint=" + acs.baseUrl() + "/;accesskey="
                + Base64.getEncoder().encodeToString(KEY);
        sender = TestEmails.sender(
                TestEmails.props(EmailProperties.Provider.AZURE, acs.baseUrl() + "/", connectionString));
    }

    @Test
    void sendsTheEmail_signedWithTheAccessKey() throws Exception {
        acs.stubFor(post(urlPathEqualTo("/emails:send"))
                .willReturn(aResponse().withStatus(202).withHeader("Operation-Location", acs.baseUrl() + "/op/1")));

        sender.send(TestEmails.message());

        acs.verify(postRequestedFor(urlEqualTo(SEND))
                .withHeader("x-ms-date", equalTo("Wed, 30 Sep 2026 18:00:00 GMT"))
                .withRequestBody(equalToJson("""
                        {"senderAddress": "no-reply@mail.northline.test",
                         "content": {"subject": "Rejoignez Prairie Wrench sur Northline", "plainText": "Bonjour",
                                     "html": "<p>Bonjour</p>"},
                         "recipients": {"to": [{"address": "sam@example.com", "displayName": "Sam Lée"}]},
                         "replyTo": [{"address": "support@northline.test"}],
                         "headers": {"List-Unsubscribe": "<http://localhost:8080/api/v1/email/unsubscribe?t=abc>",
                                     "List-Unsubscribe-Post": "List-Unsubscribe=One-Click"},
                         "userEngagementTrackingDisabled": true}
                        """)));
        var request = acs.getAllServeEvents().getFirst().getRequest();
        var hash = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(request.getBody()));
        assertThat(request.getHeader("x-ms-content-sha256")).isEqualTo(hash);
        var toSign = "POST\n" + SEND + "\nWed, 30 Sep 2026 18:00:00 GMT;localhost:" + acs.port() + ";" + hash;
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        var signature = Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        assertThat(request.getHeader("Authorization"))
                .isEqualTo("HMAC-SHA256 SignedHeaders=x-ms-date;host;x-ms-content-sha256&Signature=" + signature);
    }

    @Test
    void badRequest_isRejected_withoutRetry() {
        acs.stubFor(post(urlPathEqualTo("/emails:send"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                "{\"error\":{\"code\":\"InvalidRecipient\",\"message\":\"Invalid email address.\"}}")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.REJECTED);
        acs.verify(1, postRequestedFor(urlPathEqualTo("/emails:send")));
    }

    @Test
    void throttling_isRetried_thenSucceeds() {
        acs.stubFor(post(urlPathEqualTo("/emails:send"))
                .inScenario("throttled")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("ok"));
        acs.stubFor(post(urlPathEqualTo("/emails:send"))
                .inScenario("throttled")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(202)));

        sender.send(TestEmails.message());

        acs.verify(2, postRequestedFor(urlPathEqualTo("/emails:send")));
    }

    @Test
    void serverError_everyTime_isUnavailable() {
        acs.stubFor(post(urlPathEqualTo("/emails:send")).willReturn(aResponse().withStatus(503)));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
        acs.verify(3, postRequestedFor(urlPathEqualTo("/emails:send")));
    }
}
