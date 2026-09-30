package ca.northline.auth.sms;

import ca.northline.auth.application.SmsDeliveryFailed;
import ca.northline.auth.application.SmsSender;
import ca.northline.auth.domain.OtpChallenge.Channel;
import ca.northline.auth.domain.PhoneNumber;
import ca.northline.sms.SmsTransport;
import java.util.Locale;

/**
 * {@link SmsSender} over the shared SMS library ({@code server/sms}, S-27): the code worded by {@link CodeMessages}
 * (one GSM-7 segment; the voice text reads the digits one by one), sent by the provider's {@link SmsTransport}. The
 * library's failure kinds map one to one onto auth's {@link SmsDeliveryFailed}.
 */
abstract class TransportSmsSender implements SmsSender {

    private final SmsTransport transport;

    TransportSmsSender(SmsTransport transport) {
        this.transport = transport;
    }

    @Override
    public void sendCode(PhoneNumber to, String code, Channel channel, Locale locale) {
        var messages = CodeMessages.of(locale);
        try {
            if (channel == Channel.VOICE) {
                transport.call(to.e164(), messages.voice(code), locale);
            } else {
                transport.sendText(to.e164(), messages.sms(code));
            }
        } catch (ca.northline.sms.SmsDeliveryFailed e) {
            var kind = switch (e.getKind()) {
                case UNDELIVERABLE_NUMBER -> SmsDeliveryFailed.Kind.UNDELIVERABLE_NUMBER;
                case PROVIDER_UNAVAILABLE -> SmsDeliveryFailed.Kind.PROVIDER_UNAVAILABLE;
            };
            throw new SmsDeliveryFailed(kind, String.valueOf(e.getMessage()), e);
        }
    }
}
