package ca.northline.sms.aws;

import ca.northline.sms.PhoneNumbers;
import ca.northline.sms.SmsDeliveryFailed;
import ca.northline.sms.SmsDeliveryFailed.Kind;
import ca.northline.sms.SmsTransport;
import ca.northline.sms.SpokenLanguage;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ConflictException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ConflictExceptionReason;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.MessageType;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ValidationException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ValidationExceptionReason;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.VoiceId;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.VoiceMessageBodyTextType;

/**
 * {@code northline.sms.provider=aws}: AWS End User Messaging SMS and voice (API {@code pinpoint-sms-voice-v2}, what
 * Amazon SNS uses for SMS underneath) — {@code SendTextMessage} (transactional) and {@code SendVoiceMessage} (Polly
 * voice of the language). Credentials from the SDK default chain (workload identity), region {@code SMS_REGION}.
 */
@Slf4j
public final class AwsSmsTransport implements SmsTransport {

    private static final Set<ValidationExceptionReason> BAD_NUMBER =
            Set.of(ValidationExceptionReason.CANNOT_PARSE, ValidationExceptionReason.INVALID_PARAMETER);
    private static final Set<ConflictExceptionReason> REFUSED_NUMBER = Set.of(
            ConflictExceptionReason.DESTINATION_PHONE_NUMBER_OPTED_OUT,
            ConflictExceptionReason.DESTINATION_PHONE_NUMBER_BLOCKED_BY_PROTECT_NUMBER_OVERRIDE);

    private final PinpointSmsVoiceV2Client client;
    private final String from;
    private final String voiceFrom;

    public AwsSmsTransport(PinpointSmsVoiceV2Client client, String from, String voiceFrom) {
        this.client = client;
        this.from = from;
        this.voiceFrom = voiceFrom;
    }

    @Override
    public String sendText(String to, String body) {
        try {
            var id = client.sendTextMessage(r -> r.destinationPhoneNumber(to)
                            .originationIdentity(from)
                            .messageType(MessageType.TRANSACTIONAL)
                            .messageBody(body))
                    .messageId();
            log.info("AWS SMS to {} accepted: {}", PhoneNumbers.masked(to), id);
            return id;
        } catch (SdkException e) {
            throw failed("SMS", e);
        }
    }

    @Override
    public String call(String to, String spokenText, Locale locale) {
        var language = SpokenLanguage.of(locale);
        try {
            var id = client.sendVoiceMessage(r -> r.destinationPhoneNumber(to)
                            .originationIdentity(voiceFrom)
                            .messageBodyTextType(VoiceMessageBodyTextType.TEXT)
                            .voiceId(VoiceId.fromValue(language.pollyVoice().toUpperCase(Locale.ROOT)))
                            .messageBody(spokenText))
                    .messageId();
            log.info("AWS VOICE to {} accepted: {}", PhoneNumbers.masked(to), id);
            return id;
        } catch (SdkException e) {
            throw failed("VOICE", e);
        }
    }

    private static SmsDeliveryFailed failed(String channel, SdkException e) {
        var kind = switch (e) {
            case ValidationException v ->
                BAD_NUMBER.contains(v.reason())
                                && v.fields() != null
                                && v.fields().stream()
                                        .anyMatch(f -> "DestinationPhoneNumber".equalsIgnoreCase(f.name()))
                        ? Kind.UNDELIVERABLE_NUMBER
                        : Kind.PROVIDER_UNAVAILABLE;
            case ConflictException c ->
                REFUSED_NUMBER.contains(c.reason()) ? Kind.UNDELIVERABLE_NUMBER : Kind.PROVIDER_UNAVAILABLE;
            default -> Kind.PROVIDER_UNAVAILABLE;
        };
        return new SmsDeliveryFailed(kind, "AWS %s: %s".formatted(channel, e.getMessage()), e);
    }
}
