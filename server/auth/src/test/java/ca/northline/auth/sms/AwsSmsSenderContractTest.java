package ca.northline.auth.sms;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ca.northline.auth.application.SmsDeliveryFailed;
import ca.northline.auth.application.SmsDeliveryFailed.Kind;
import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import ca.northline.platform.SmsProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;

/**
 * The AWS End User Messaging SMS and voice adapter against a WireMock stand-in of its JSON 1.0 API
 * ({@code X-Amz-Target: PinpointSMSVoiceV2.*}), with the client {@link SmsConfig} builds for {@code SMS_PROVIDER=aws}.
 */
class AwsSmsSenderContractTest {

    private static final PhoneNumber TO = PhoneNumber.parse("587-555-0148").orElseThrow();
    private static final String TARGET = "X-Amz-Target";

    static WireMockServer aws;
    private PinpointSmsVoiceV2Client client;
    private SmsSender sender;

    @BeforeAll
    static void start() {
        aws = new WireMockServer(wireMockConfig().dynamicPort());
        aws.start();
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @AfterAll
    static void stop() {
        aws.stop();
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @BeforeEach
    void wire() {
        aws.resetAll();
        var props = new SmsProperties(
                SmsProperties.Provider.AWS, "phone-0123456789abcdef", null, null, null, "ca-central-1", aws.baseUrl());
        client = new SmsConfig.Aws().pinpointSmsVoiceV2Client(props);
        sender = new SmsConfig.Aws().awsSmsSender(client, props);
    }

    @AfterEach
    void close() {
        client.close();
    }

    @Test
    void sms_isATransactionalTextMessage_inFrench() {
        aws.stubFor(post("/")
                .withHeader(TARGET, equalTo("PinpointSMSVoiceV2.SendTextMessage"))
                .willReturn(json(200, "{\"MessageId\":\"m-1\"}")));

        sender.sendCode(TO, "123456", Channel.SMS, Locale.CANADA_FRENCH);

        aws.verify(postRequestedFor(urlEqualTo("/"))
                .withHeader("Authorization", containing("/ca-central-1/sms-voice/aws4_request"))
                .withRequestBody(matchingJsonPath("$.DestinationPhoneNumber", equalTo("+15875550148")))
                .withRequestBody(matchingJsonPath("$.OriginationIdentity", equalTo("phone-0123456789abcdef")))
                .withRequestBody(matchingJsonPath("$.MessageType", equalTo("TRANSACTIONAL")))
                .withRequestBody(
                        matchingJsonPath("$.MessageBody", containing("votre code de vérification est 123456"))));
    }

    @Test
    void voice_readsTheDigits_withAnEnglishVoice() {
        aws.stubFor(post("/")
                .withHeader(TARGET, equalTo("PinpointSMSVoiceV2.SendVoiceMessage"))
                .willReturn(json(200, "{\"MessageId\":\"v-1\"}")));

        sender.sendCode(TO, "908172", Channel.VOICE, Locale.CANADA);

        aws.verify(postRequestedFor(urlEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.VoiceId", equalTo("JOANNA")))
                .withRequestBody(matchingJsonPath("$.MessageBodyTextType", equalTo("TEXT")))
                .withRequestBody(matchingJsonPath("$.MessageBody", containing("9, 0, 8, 1, 7, 2"))));
    }

    @Test
    void invalidDestination_isUndeliverable() {
        aws.stubFor(post("/").willReturn(error("ValidationException", """
                {"__type":"ValidationException","Message":"Invalid destination","Reason":"INVALID_PARAMETER",\
                "Fields":[{"Name":"DestinationPhoneNumber","Message":"not a valid phone number"}]}""")));

        assertThat(kindOf(Channel.SMS)).isEqualTo(Kind.UNDELIVERABLE_NUMBER);
    }

    @Test
    void optedOut_isUndeliverable() {
        aws.stubFor(post("/").willReturn(error("ConflictException", """
                {"__type":"ConflictException","Message":"opted out","Reason":"DESTINATION_PHONE_NUMBER_OPTED_OUT",\
                "ResourceType":"opt-out-list","ResourceId":"Default"}""")));

        assertThat(kindOf(Channel.SMS)).isEqualTo(Kind.UNDELIVERABLE_NUMBER);
    }

    @Test
    void throttlingOrOurOwnConfiguration_isUnavailable() {
        aws.stubFor(post("/").willReturn(error("ThrottlingException", """
                {"__type":"ThrottlingException","Message":"Rate exceeded"}""")));
        assertThat(kindOf(Channel.SMS)).isEqualTo(Kind.PROVIDER_UNAVAILABLE);

        aws.stubFor(post("/").willReturn(error("ValidationException", """
                {"__type":"ValidationException","Message":"no voice","Reason":"VOICE_CAPABILITY_NOT_AVAILABLE",\
                "Fields":[{"Name":"OriginationIdentity","Message":"no voice capability"}]}""")));
        assertThat(kindOf(Channel.VOICE)).isEqualTo(Kind.PROVIDER_UNAVAILABLE);

        aws.stubFor(post("/").willReturn(aResponse().withStatus(500).withBody("{}")));
        assertThat(kindOf(Channel.SMS)).isEqualTo(Kind.PROVIDER_UNAVAILABLE);
    }

    private Kind kindOf(Channel channel) {
        var thrown =
                catchThrowableOfType(SmsDeliveryFailed.class, () -> sender.sendCode(TO, "1", channel, Locale.CANADA));
        assertThat(thrown).isNotNull();
        return thrown.getKind();
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/x-amz-json-1.0")
                .withBody(body);
    }

    private static ResponseDefinitionBuilder error(String type, String body) {
        return json(400, body).withHeader("x-amzn-ErrorType", type);
    }
}
