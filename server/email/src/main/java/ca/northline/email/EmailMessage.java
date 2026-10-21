package ca.northline.email;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One rendered email to one recipient. The sender ({@code EMAIL_FROM}) and Reply-To are the adapter's configuration.
 *
 * @param to the recipient
 * @param subject subject line
 * @param html HTML body (inline styles only)
 * @param text plain-text alternative
 * @param headers extra headers, e.g. {@code List-Unsubscribe}, {@code List-Unsubscribe-Post}
 * @param tag template name; providers that support it get it as a category/tag for their statistics
 * @param idempotencyKey the Mailer's delivery key; providers that de-duplicate on a key (Resend's {@code
 *     Idempotency-Key}) get it, so a retried send is not delivered twice; null = none
 */
public record EmailMessage(
        EmailAddress to,
        String subject,
        String html,
        String text,
        Map<String, String> headers,
        String tag,
        @Nullable String idempotencyKey) {

    public static final String LIST_UNSUBSCRIBE = "List-Unsubscribe";
    public static final String LIST_UNSUBSCRIBE_POST = "List-Unsubscribe-Post";

    public EmailMessage {
        if (subject.isBlank() || subject.contains("\n") || subject.contains("\r")) {
            throw new IllegalArgumentException("Subject must be one non-blank line");
        }
        headers = Map.copyOf(headers);
    }

    /** A message without an idempotency key (sent outside the Mailer). */
    public EmailMessage(
            EmailAddress to, String subject, String html, String text, Map<String, String> headers, String tag) {
        this(to, subject, html, text, headers, tag, null);
    }
}
