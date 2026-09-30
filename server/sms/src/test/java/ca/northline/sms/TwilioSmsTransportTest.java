package ca.northline.sms;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ca.northline.platform.SmsProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shared Twilio adapter against a WireMock stand-in (auth's TwilioSmsSenderContractTest covers the code texts over
 * the same adapter): free text, Messaging Service sender, voice in French, failure kinds.
 */
class TwilioSmsTransportTest {

    static final String ACCOUNT = "ACtest-account-sid";
    static final String MESSAGES = "/2010-04-01/Accounts/" + ACCOUNT + "/Messages.json";
    static final String CALLS = "/2010-04-01/Accounts/" + ACCOUNT + "/Calls.json";
    static WireMockServer twilio;

    @BeforeAll
    static void start() {
        twilio = new WireMockServer(wireMockConfig().dynamicPort());
        twilio.start();
    }

    @AfterAll
    static void stop() {
        twilio.stop();
    }

    @BeforeEach
    void reset() {
        twilio.resetAll();
    }

    SmsTransport transport(String from, String voiceFrom) {
        return SmsTransports.twilio(new SmsProperties(
                SmsProperties.Provider.TWILIO,
                from,
                voiceFrom,
                ACCOUNT,
                "test-token-not-real",
                null,
                twilio.baseUrl()));
    }

    @Test
    void sendsAnyTextThroughAMessagingService() {
        twilio.stubFor(post(urlEqualTo(MESSAGES)).willReturn(json(201, "{\"sid\":\"SM1\",\"status\":\"accepted\"}")));
        var id = transport("MG0123456789abcdef0123456789abcdef", "+15875550101")
                .sendText("+14035550148", "Northline: a payout of $814.37 is on its way.");
        assertThat(id).isEqualTo("SM1");
        twilio.verify(postRequestedFor(urlEqualTo(MESSAGES))
                .withRequestBody(containing("MessagingServiceSid=MG0123456789abcdef0123456789abcdef"))
                .withRequestBody(containing("Body=Northline%3A+a+payout+of+%24814.37")));
    }

    @Test
    void callsReadTheTextInFrench() {
        twilio.stubFor(post(urlEqualTo(CALLS)).willReturn(json(201, "{\"sid\":\"CA1\",\"status\":\"queued\"}")));
        transport("+15875550100", "").call("+14035550148", "Bonjour & à bientôt", Locale.CANADA_FRENCH);
        twilio.verify(postRequestedFor(urlEqualTo(CALLS))
                .withRequestBody(containing("Polly.Chantal"))
                .withRequestBody(containing("fr-CA"))
                .withRequestBody(containing("From=%2B15875550100")));
    }

    @Test
    void optedOutIsUndeliverable_serverErrorIsUnavailable() {
        twilio.stubFor(post(urlEqualTo(MESSAGES))
                .willReturn(json(400, "{\"code\":21610,\"message\":\"unsubscribed recipient\",\"status\":400}")));
        var optedOut = catchThrowableOfType(
                SmsDeliveryFailed.class, () -> transport("+15875550100", "").sendText("+14035550148", "x"));
        assertThat(optedOut.getKind()).isEqualTo(SmsDeliveryFailed.Kind.UNDELIVERABLE_NUMBER);

        twilio.stubFor(post(urlEqualTo(MESSAGES)).willReturn(aResponse().withStatus(503)));
        var down = catchThrowableOfType(
                SmsDeliveryFailed.class, () -> transport("+15875550100", "").sendText("+14035550148", "x"));
        assertThat(down.getKind()).isEqualTo(SmsDeliveryFailed.Kind.PROVIDER_UNAVAILABLE);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
