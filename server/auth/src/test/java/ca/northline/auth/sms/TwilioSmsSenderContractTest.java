package ca.northline.auth.sms;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ca.northline.auth.application.SmsDeliveryFailed;
import ca.northline.auth.application.SmsDeliveryFailed.Kind;
import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import ca.northline.platform.SmsProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Twilio adapter against a WireMock stand-in of the Twilio REST API (request shapes and error bodies as documented
 * for API 2010-04-01): wired exactly as {@link SmsConfig} does for {@code SMS_PROVIDER=twilio}.
 */
class TwilioSmsSenderContractTest {

    private static final String ACCOUNT = "ACtest-account-sid";
    private static final String TOKEN = "test-auth-token";
    private static final String FROM = "+15875550100";
    private static final String MESSAGES = "/2010-04-01/Accounts/" + ACCOUNT + "/Messages.json";
    private static final String CALLS = "/2010-04-01/Accounts/" + ACCOUNT + "/Calls.json";
    private static final PhoneNumber TO = PhoneNumber.parse("+1 403 555 0148").orElseThrow();

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

    @Test
    void sms_postsTheEnglishMessage_withBasicAuth() {
        twilio.stubFor(post(MESSAGES).willReturn(json(201, """
                {"sid":"SM0001","status":"queued","to":"+14035550148"}""")));

        sender(FROM, null).sendCode(TO, "123456", Channel.SMS, Locale.CANADA);

        twilio.verify(postRequestedFor(urlEqualTo(MESSAGES))
                .withBasicAuth(new BasicCredentials(ACCOUNT, TOKEN))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withFormParam("To", equalTo("+14035550148"))
                .withFormParam("From", equalTo(FROM))
                .withFormParam(
                        "Body",
                        equalTo("Northline: your verification code is 123456. It expires in 10 minutes. Never share"
                                + " it.")));
    }

    @Test
    void sms_inFrench_throughAMessagingService() {
        twilio.stubFor(post(MESSAGES).willReturn(json(201, """
                {"sid":"SM0002","status":"accepted"}""")));

        sender("MG0123456789abcdef0123456789abcdef", "+15875550199")
                .sendCode(TO, "654321", Channel.SMS, Locale.CANADA_FRENCH);

        twilio.verify(postRequestedFor(urlEqualTo(MESSAGES))
                .withFormParam("MessagingServiceSid", equalTo("MG0123456789abcdef0123456789abcdef"))
                .withFormParam("Body", containing("votre code de vérification est 654321")));
    }

    @Test
    void voice_placesACallThatReadsTheDigits_inTheCallersLanguage() {
        twilio.stubFor(post(CALLS).willReturn(json(201, """
                {"sid":"CA0001","status":"queued"}""")));

        sender(FROM, null).sendCode(TO, "120034", Channel.VOICE, Locale.CANADA_FRENCH);

        var request = twilio.findAll(postRequestedFor(urlEqualTo(CALLS))).getFirst();
        var form = URLDecoder.decode(request.getBodyAsString(), StandardCharsets.UTF_8);
        assertThat(form).contains("To=+14035550148", "From=" + FROM);
        assertThat(form)
                .contains("<Response><Pause length=\"1\"/><Say voice=\"Polly.Chantal\" language=\"fr-CA\">")
                .contains("Votre code de vérification est : 1, 2, 0, 0, 3, 4.")
                .endsWith("</Say></Response>");
    }

    @Test
    void invalidNumber_isUndeliverable() {
        twilio.stubFor(post(MESSAGES).willReturn(json(400, """
                {"code":21211,"message":"Invalid 'To' Phone Number: +1403555XXXX","more_info":"https://www.twilio.com/docs/errors/21211","status":400}""")));

        assertThat(failure(() -> sender(FROM, null).sendCode(TO, "123456", Channel.SMS, Locale.CANADA)))
                .isEqualTo(Kind.UNDELIVERABLE_NUMBER);
    }

    @Test
    void landlineOrOptedOut_isUndeliverable_forVoiceToo() {
        twilio.stubFor(post(MESSAGES).willReturn(json(400, """
                {"code":21614,"message":"'To' number is not a valid mobile number","status":400}""")));
        twilio.stubFor(post(CALLS).willReturn(json(400, """
                {"code":13224,"message":"Invalid phone number","status":400}""")));

        assertThat(failure(() -> sender(FROM, null).sendCode(TO, "1", Channel.SMS, Locale.CANADA)))
                .isEqualTo(Kind.UNDELIVERABLE_NUMBER);
        assertThat(failure(() -> sender(FROM, null).sendCode(TO, "1", Channel.VOICE, Locale.CANADA)))
                .isEqualTo(Kind.UNDELIVERABLE_NUMBER);
    }

    @Test
    void providerErrors_areUnavailable() {
        twilio.stubFor(post(MESSAGES).willReturn(json(401, """
                {"code":20003,"message":"Authenticate","status":401}""")));
        assertThat(failure(() -> sender(FROM, null).sendCode(TO, "1", Channel.SMS, Locale.CANADA)))
                .isEqualTo(Kind.PROVIDER_UNAVAILABLE);

        twilio.stubFor(post(MESSAGES).willReturn(json(400, """
                {"code":21408,"message":"Permission to send an SMS has not been enabled for the region","status":400}""")));
        assertThat(failure(() -> sender(FROM, null).sendCode(TO, "1", Channel.SMS, Locale.CANADA)))
                .isEqualTo(Kind.PROVIDER_UNAVAILABLE);

        twilio.stubFor(post(MESSAGES).willReturn(aResponse().withStatus(503).withBody("upstream down")));
        assertThat(failure(() -> sender(FROM, null).sendCode(TO, "1", Channel.SMS, Locale.CANADA)))
                .isEqualTo(Kind.PROVIDER_UNAVAILABLE);
    }

    @Test
    void unreachable_isUnavailable() {
        var closed = new SmsConfig()
                .twilioSmsSender(new SmsProperties(
                        SmsProperties.Provider.TWILIO, FROM, null, ACCOUNT, TOKEN, null, "http://127.0.0.1:1"));

        assertThat(failure(() -> closed.sendCode(TO, "1", Channel.SMS, Locale.CANADA)))
                .isEqualTo(Kind.PROVIDER_UNAVAILABLE);
    }

    private static SmsSender sender(String from, @Nullable String voiceFrom) {
        return new SmsConfig()
                .twilioSmsSender(new SmsProperties(
                        SmsProperties.Provider.TWILIO, from, voiceFrom, ACCOUNT, TOKEN, null, twilio.baseUrl()));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static Kind failure(ThrowingCallable call) {
        var thrown = catchThrowableOfType(SmsDeliveryFailed.class, call);
        assertThat(thrown).as("SmsDeliveryFailed").isNotNull();
        return thrown.getKind();
    }

    @Test
    void missingCredentials_failFast_namingTheVariables() {
        assertThatThrownBy(() -> new SmsConfig()
                        .twilioSmsSender(
                                new SmsProperties(SmsProperties.Provider.TWILIO, null, null, null, null, null, null)))
                .hasMessageContaining("SMS_ACCOUNT_ID")
                .hasMessageContaining("SMS_AUTH_TOKEN")
                .hasMessageContaining("SMS_FROM");
    }
}
