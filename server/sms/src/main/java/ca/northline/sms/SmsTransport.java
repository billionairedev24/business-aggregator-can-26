package ca.northline.sms;

import java.util.Locale;

/**
 * Hands one text message or one voice call to the provider. Returns once the provider accepted it (its message or
 * call id); delivery to the handset is the provider's business. No retries inside: a retried request can deliver twice,
 * so callers decide (auth: the person resends; the worker: its once-per-recipient claim, then a Kafka retry).
 */
public interface SmsTransport {

    /**
     * @param to E.164 number, e.g. {@code +14035550148}
     * @throws SmsDeliveryFailed when the number can't get it or the provider can't be reached
     */
    String sendText(String to, String body);

    /**
     * Places a call that reads {@code spokenText} in the language of {@code locale} (French for {@code fr}, English
     * otherwise — {@link SpokenLanguage}).
     *
     * @throws SmsDeliveryFailed when the number can't get it or the provider can't be reached
     */
    String call(String to, String spokenText, Locale locale);
}
