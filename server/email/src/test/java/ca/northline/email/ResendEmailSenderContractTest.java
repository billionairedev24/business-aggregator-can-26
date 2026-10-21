package ca.northline.email;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ca.northline.email.resend.ResendEmailSender;
import ca.northline.platform.EmailProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code EMAIL_PROVIDER=resend} against a WireMock stand-in of Resend's {@code POST /emails} (request and error shapes
 * from Resend's OpenAPI description and API reference). Fake API key; never sent to the real Resend.
 */
class ResendEmailSenderContractTest {

    static final String SEND = "/emails";
    static final String KEY = "re_fake_key_for_tests";
    static final String JSON = "application/json";
    static WireMockServer resend;
    EmailSender sender;

    @BeforeAll
    static void start() {
        resend = new WireMockServer(wireMockConfig().dynamicPort());
        resend.start();
    }

    @AfterAll
    static void stop() {
        resend.stop();
    }

    @BeforeEach
    void wire() {
        resend.resetAll();
        sender = TestEmails.sender(TestEmails.props(EmailProperties.Provider.RESEND, resend.baseUrl(), KEY));
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder error(int status, String name, String msg) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", JSON)
                .withBody("{\"statusCode\":" + status + ",\"name\":\"" + name + "\",\"message\":\"" + msg + "\"}");
    }

    static EmailMessage keyed(String key) {
        var m = TestEmails.message();
        return new EmailMessage(m.to(), m.subject(), m.html(), m.text(), m.headers(), m.tag(), key);
    }

    @Test
    void accepted_sendsTheDocumentedBody_bearerKey_andIdempotencyKey() {
        resend.stubFor(post(SEND).willReturn(okJson()));

        sender.send(keyed("evt-1:user-1"));

        resend.verify(postRequestedFor(urlEqualTo(SEND))
                .withHeader("Authorization", equalTo("Bearer " + KEY))
                .withHeader("Content-Type", containing(JSON))
                .withHeader("Idempotency-Key", equalTo(ResendEmailSender.idempotencyKey("evt-1:user-1")))
                .withHeader("Idempotency-Key", matching("northline-[0-9a-f]{64}"))
                .withRequestBody(equalToJson("""
                        {"from": "\\"Northline\\" <no-reply@mail.northline.test>",
                         "to": ["\\"Sam Lée\\" <sam@example.com>"],
                         "reply_to": ["support@northline.test"],
                         "subject": "Rejoignez Prairie Wrench sur Northline",
                         "html": "<p>Bonjour</p>",
                         "text": "Bonjour",
                         "headers": {"List-Unsubscribe": "<http://localhost:8080/api/v1/email/unsubscribe?t=abc>",
                                     "List-Unsubscribe-Post": "List-Unsubscribe=One-Click"},
                         "tags": [{"name": "template", "value": "team-invitation"}]}
                        """)));
    }

    @Test
    void noKey_noIdempotencyHeader() {
        resend.stubFor(post(SEND).willReturn(okJson()));

        sender.send(TestEmails.message());

        resend.verify(postRequestedFor(urlEqualTo(SEND)).withHeader("Idempotency-Key", absent()));
    }

    @Test
    void tooManyRequests_isRetried_withTheSameIdempotencyKey_thenAccepted() {
        resend.stubFor(post(SEND)
                .inScenario("throttled")
                .whenScenarioStateIs(STARTED)
                .willReturn(error(
                        429, "rate_limit_exceeded", "Too many requests. You can only make 2 requests per second."))
                .willSetStateTo("ok"));
        resend.stubFor(
                post(SEND).inScenario("throttled").whenScenarioStateIs("ok").willReturn(okJson()));

        sender.send(keyed("evt-2:user-1"));

        resend.verify(
                2,
                postRequestedFor(urlEqualTo(SEND))
                        .withHeader("Idempotency-Key", equalTo(ResendEmailSender.idempotencyKey("evt-2:user-1"))));
    }

    @Test
    void serverError_everyTime_isUnavailable_afterThreeTries() {
        resend.stubFor(post(SEND).willReturn(error(500, "internal_server_error", "An unexpected error occurred.")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
        resend.verify(3, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void validationError_isRejected_withoutRetry_andResendsMessageMasked() {
        resend.stubFor(post(SEND)
                .willReturn(
                        error(422, "validation_error", "Invalid `to` field: sam@example.com is not a valid address.")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.REJECTED);
        assertThat(failure.getMessage())
                .isEqualTo("Resend 422 validation_error: Invalid `to` field: s***@example.com is not a valid address.")
                .doesNotContain("sam@example.com");
        resend.verify(1, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void badApiKey_isUnavailable_likeEveryProvidersCredentialProblem() {
        resend.stubFor(
                post(SEND).willReturn(error(401, "missing_api_key", "Missing API key in the authorization header")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
        assertThat(failure.getMessage())
                .isEqualTo("Resend 401 missing_api_key: Missing API key in the authorization header");
    }

    @Test
    void unverifiedDomain_isUnavailable_withResendsMessage() {
        resend.stubFor(post(SEND)
                .willReturn(error(
                        403,
                        "validation_error",
                        "The mail.northline.test domain is not verified. Please, add and verify your domain.")));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
        assertThat(failure.getMessage()).contains("403 validation_error", "domain is not verified");
    }

    @Test
    void idempotencyConflicts_concurrentIsRetried_reusedIsRejected() {
        resend.stubFor(post(SEND)
                .willReturn(error(
                        409, "invalid_idempotent_request", "Same idempotency key used with a different payload.")));
        var reused = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(keyed("evt-3:user-1")));
        assertThat(reused.getKind()).isEqualTo(EmailDeliveryFailed.Kind.REJECTED);

        resend.resetAll();
        resend.stubFor(post(SEND)
                .willReturn(error(
                        409,
                        "concurrent_idempotent_requests",
                        "Same idempotency key used while original request is still in progress.")));
        var concurrent = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(keyed("evt-3:user-1")));
        assertThat(concurrent.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
        resend.verify(3, postRequestedFor(urlEqualTo(SEND)));
    }

    @Test
    void templates_renderThroughTheMailer_intoResendsBody() {
        resend.stubFor(post(SEND).willReturn(okJson()));
        var mailer = new DefaultMailer(sender, EmailTemplatesTest.TEMPLATES, SentEmails.NONE);

        var outcome = mailer.send(new Mailer.Delivery(
                "account-verification:u-1:c-1",
                EmailAddress.of("amara@example.com"),
                EmailContent.samples().get("account-verification"),
                Locale.CANADA,
                null));

        assertThat(outcome).isEqualTo(Mailer.Outcome.SENT);
        resend.verify(postRequestedFor(urlEqualTo(SEND))
                .withHeader(
                        "Idempotency-Key", equalTo(ResendEmailSender.idempotencyKey("account-verification:u-1:c-1")))
                .withRequestBody(matchingJsonPath("$.to[0]", equalTo("amara@example.com")))
                .withRequestBody(matchingJsonPath("$.html", containing("482913")))
                .withRequestBody(matchingJsonPath("$.text", containing("482913")))
                .withRequestBody(matchingJsonPath("$.tags[0].value", equalTo("account-verification")))
                .withRequestBody(matchingJsonPath("$.headers", absent())));
    }

    @Test
    void commercialMail_carriesTheOneClickUnsubscribeHeaders() {
        resend.stubFor(post(SEND).willReturn(okJson()));
        var mailer = new DefaultMailer(sender, EmailTemplatesTest.TEMPLATES, SentEmails.NONE);
        var unsubscribe = java.net.URI.create("http://localhost:8080/api/v1/email/unsubscribe?t=x");

        mailer.send(new Mailer.Delivery(
                "evt-9:user-9",
                EmailAddress.of("owner@example.com"),
                EmailContent.samples().get("payout-sent"),
                Locale.CANADA,
                unsubscribe));

        resend.verify(postRequestedFor(urlEqualTo(SEND))
                .withRequestBody(matchingJsonPath("$.headers['List-Unsubscribe']", equalTo("<" + unsubscribe + ">")))
                .withRequestBody(
                        matchingJsonPath("$.headers['List-Unsubscribe-Post']", equalTo("List-Unsubscribe=One-Click"))));
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson() {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", JSON)
                .withBody("{\"id\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}");
    }
}
