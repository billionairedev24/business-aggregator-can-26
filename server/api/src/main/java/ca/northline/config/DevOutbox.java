package ca.northline.config;

import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import ca.northline.sms.SmsTransport;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * LOCAL PROFILE ONLY (S-117): what the api's email and SMS fakes sent — recorded by {@link DevOutboxConfig}'s proxies
 * around the configured {@link EmailSender} and {@link SmsTransport}, read at {@code GET /api/v1/dev/outbox} ({@code
 * config.web.DevOutboxController}) by the end-to-end suite. Bounded, in memory; the messages are still delivered as
 * before (Mailpit or the log).
 */
public final class DevOutbox {

    static final int CAPACITY = 300;

    /** {@code kind} = email | sms | voice; {@code subject} empty for texts. */
    public record Message(
            String kind,
            String to,
            String subject,
            String text,
            @Nullable String tag,
            Instant at) {}

    private final Deque<Message> messages = new ArrayDeque<>();
    private final Clock clock;

    DevOutbox(Clock clock) {
        this.clock = clock;
    }

    synchronized void record(String kind, String to, String subject, String text, @Nullable String tag) {
        messages.addFirst(new Message(kind, to, subject, text, tag, clock.instant()));
        while (messages.size() > CAPACITY) {
            messages.removeLast();
        }
    }

    /** Newest first. An email address matches case-insensitively; a phone number on its last ten digits. */
    public synchronized List<Message> to(String to) {
        var wanted = key(to);
        return messages.stream().filter(m -> key(m.to()).equals(wanted)).toList();
    }

    private static String key(String to) {
        if (to.contains("@")) {
            return to.trim().toLowerCase(Locale.ROOT);
        }
        var digits = to.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    /** A call that went through to an {@link EmailSender} or {@link SmsTransport} (see {@link DevOutboxConfig}). */
    void observe(String method, @Nullable Object[] args) {
        switch (method) {
            case "send" -> {
                if (args.length == 1 && args[0] instanceof EmailMessage m) {
                    record("email", m.to().address(), m.subject(), m.text(), m.tag());
                }
            }
            case "sendText" -> {
                if (args.length == 2 && args[0] instanceof String to && args[1] instanceof String body) {
                    record("sms", to, "", body, null);
                }
            }
            case "call" -> {
                if (args.length >= 2 && args[0] instanceof String to && args[1] instanceof String spoken) {
                    record("voice", to, "", spoken, null);
                }
            }
            default -> {
                // anything else (close, toString) isn't a message
            }
        }
    }
}
