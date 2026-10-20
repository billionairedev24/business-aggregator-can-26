package ca.northline.auth.sms;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * LOCAL PROFILE ONLY (S-117): the last codes the local SMS fake "sent", so the end-to-end suite (and a developer) can
 * read the code for a number instead of grepping the log — {@code GET /api/auth/dev/outbox?to=…} ({@link
 * DevOutboxController}). S-154: also the codes of the account emails (channel {@code email}; the email itself goes to
 * Mailpit). Bounded and in memory; the bean doesn't exist outside {@code local} (DevOnlyBeansTest).
 */
public final class DevOutbox {

    static final int CAPACITY = 200;

    /** One code: the number it went to (E.164), {@code sms} or {@code voice}, the code, when. */
    public record Sent(String to, String channel, String code, Instant at) {}

    private final Deque<Sent> sent = new ArrayDeque<>();
    private final Clock clock;

    public DevOutbox(Clock clock) {
        this.clock = clock;
    }

    synchronized void record(String to, String channel, String code) {
        sent.addFirst(new Sent(to, channel, code, clock.instant()));
        while (sent.size() > CAPACITY) {
            sent.removeLast();
        }
    }

    /** S-154: the code of an account email (verification, password reset). */
    public void recordEmail(String to, String code) {
        record(to.strip().toLowerCase(java.util.Locale.ROOT), "email", code);
    }

    /**
     * Newest first; a number matches on the last ten digits, so any spelling of it works; an email address matches
     * case-insensitively.
     */
    public synchronized List<Sent> to(String to) {
        if (to.contains("@")) {
            var address = to.strip().toLowerCase(java.util.Locale.ROOT);
            return sent.stream().filter(s -> s.to().equals(address)).toList();
        }
        var digits = lastTen(to);
        return sent.stream()
                .filter(s -> !s.to().contains("@") && lastTen(s.to()).equals(digits))
                .toList();
    }

    private static String lastTen(String phone) {
        var digits = phone.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }
}
