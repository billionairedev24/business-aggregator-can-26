package ca.northline.sms;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code northline.sms.provider=local}: writes each message to the log instead of sending it. Refused under
 * staging/prod ({@link SmsTransports#local}).
 */
@Slf4j
public final class LoggingSmsTransport implements SmsTransport {

    private final AtomicLong ids = new AtomicLong();

    @Override
    public String sendText(String to, String body) {
        log.warn("[SMS] to {}: {} (not sent — local SMS fake)", PhoneNumbers.masked(to), body);
        return "local-" + ids.incrementAndGet();
    }

    @Override
    public String call(String to, String spokenText, Locale locale) {
        log.warn("[VOICE] to {} ({}): {} (not called — local SMS fake)", PhoneNumbers.masked(to), locale, spokenText);
        return "local-" + ids.incrementAndGet();
    }
}
