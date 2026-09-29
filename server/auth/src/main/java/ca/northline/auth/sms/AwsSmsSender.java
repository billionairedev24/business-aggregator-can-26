package ca.northline.auth.sms;

import ca.northline.auth.application.SmsDeliveryFailed;
import ca.northline.auth.application.SmsDeliveryFailed.Kind;
import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
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
 * voice Joanna / Chantal for French). Credentials from the SDK default chain (workload identity), region
 * {@code SMS_REGION}. Kept so the platform can run entirely on AWS; Twilio stays the default recommendation.
 */
@Slf4j
@RequiredArgsConstructor
class AwsSmsSender implements SmsSender {

    private static final Set<ValidationExceptionReason> BAD_NUMBER =
            Set.of(ValidationExceptionReason.CANNOT_PARSE, ValidationExceptionReason.INVALID_PARAMETER);
    private static final Set<ConflictExceptionReason> REFUSED_NUMBER = Set.of(
            ConflictExceptionReason.DESTINATION_PHONE_NUMBER_OPTED_OUT,
            ConflictExceptionReason.DESTINATION_PHONE_NUMBER_BLOCKED_BY_PROTECT_NUMBER_OVERRIDE);

    private final PinpointSmsVoiceV2Client client;
    private final String from;
    private final String voiceFrom;

    @Override
    public void sendCode(PhoneNumber to, String code, Channel channel, Locale locale) {
        var messages = CodeMessages.of(locale);
        try {
            var messageId = switch (channel) {
                case SMS ->
                    client.sendTextMessage(r -> r.destinationPhoneNumber(to.e164())
                                    .originationIdentity(from)
                                    .messageType(MessageType.TRANSACTIONAL)
                                    .messageBody(messages.sms(code)))
                            .messageId();
                case VOICE ->
                    client.sendVoiceMessage(r -> r.destinationPhoneNumber(to.e164())
                                    .originationIdentity(voiceFrom)
                                    .messageBodyTextType(VoiceMessageBodyTextType.TEXT)
                                    .voiceId(VoiceId.fromValue(
                                            messages.pollyVoice().toUpperCase(Locale.ROOT)))
                                    .messageBody(messages.voice(code)))
                            .messageId();
            };
            log.info("AWS {} to {} accepted: {}", channel, to.masked(), messageId);
        } catch (ValidationException e) {
            var aboutTheNumber = BAD_NUMBER.contains(e.reason())
                    && e.fields() != null
                    && e.fields().stream().anyMatch(f -> "DestinationPhoneNumber".equalsIgnoreCase(f.name()));
            throw failed(aboutTheNumber ? Kind.UNDELIVERABLE_NUMBER : Kind.PROVIDER_UNAVAILABLE, channel, e);
        } catch (ConflictException e) {
            throw failed(
                    REFUSED_NUMBER.contains(e.reason()) ? Kind.UNDELIVERABLE_NUMBER : Kind.PROVIDER_UNAVAILABLE,
                    channel,
                    e);
        } catch (SdkException e) {
            throw failed(Kind.PROVIDER_UNAVAILABLE, channel, e);
        }
    }

    private static SmsDeliveryFailed failed(Kind kind, Channel channel, SdkException e) {
        return new SmsDeliveryFailed(kind, "AWS %s: %s".formatted(channel, e.getMessage()), e);
    }
}
