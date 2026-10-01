package ca.northline.sms;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code northline.sms.provider=local}: writes each message to the log instead of sending it. Refused under
 * staging/prod ({@link SmsTransports#local}). S-112: the text (invitation links, codes) is written only on a developer's
 * machine ({@code local} / {@code test}); elsewhere only that a message was withheld.
 */
@Slf4j
public final class LoggingSmsTransport implements SmsTransport {

    private final AtomicLong ids = new AtomicLong();
    private final boolean revealText;

    public LoggingSmsTransport(boolean revealText) {
        this.revealText = revealText;
    }

    @Override
    public String sendText(String to, String body) {
        log.warn(
                "[SMS] to {}: {} (not sent — local SMS fake)",
                PhoneNumbers.masked(to),
                revealText ? body : "(text withheld outside a local run)");
        return "local-" + ids.incrementAndGet();
    }

    @Override
    public String call(String to, String spokenText, Locale locale) {
        log.warn(
                "[VOICE] to {} ({}): {} (not called — local SMS fake)",
                PhoneNumbers.masked(to),
                locale,
                revealText ? spokenText : "(text withheld outside a local run)");
        return "local-" + ids.incrementAndGet();
    }
}
