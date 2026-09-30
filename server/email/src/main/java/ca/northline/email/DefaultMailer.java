package ca.northline.email;

import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** The {@link Mailer}: de-duplicate, render, add headers, send; permanent rejections are logged and remembered. */
@Slf4j
@RequiredArgsConstructor
final class DefaultMailer implements Mailer {

    private final EmailSender sender;
    private final EmailTemplates templates;
    private final SentEmails sent;

    @Override
    public Outcome send(Delivery delivery) {
        var content = delivery.content();
        // Render first: a template bug must not leave a claim behind.
        var rendered = templates.render(content, delivery.locale(), delivery.unsubscribe());
        if (!sent.claim(delivery.key())) {
            log.debug("Email {} already sent ({})", content.template(), delivery.key());
            return Outcome.ALREADY_SENT;
        }
        var headers = new LinkedHashMap<String, String>();
        if (content.purpose().needsUnsubscribe() && delivery.unsubscribe() != null) {
            // RFC 2369 + RFC 8058: mailbox providers show their own "Unsubscribe" and POST to the URL in one click.
            headers.put(EmailMessage.LIST_UNSUBSCRIBE, "<" + delivery.unsubscribe() + ">");
            headers.put(EmailMessage.LIST_UNSUBSCRIBE_POST, "List-Unsubscribe=One-Click");
        }
        var message = new EmailMessage(
                delivery.to(), rendered.subject(), rendered.html(), rendered.text(), headers, content.template());
        var to = masked(delivery.to());
        try {
            sender.send(message);
        } catch (EmailDeliveryFailed e) {
            if (e.getKind() == EmailDeliveryFailed.Kind.REJECTED) {
                // The claim stays: a rejected message is not sent again.
                log.warn(
                        "Email {} to {} rejected, not retried ({}): {}",
                        content.template(),
                        to,
                        delivery.key(),
                        e.getMessage());
                return Outcome.REJECTED;
            }
            sent.release(delivery.key());
            log.warn("Email {} to {} not sent ({}): {}", content.template(), to, delivery.key(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            sent.release(delivery.key());
            throw e;
        }
        log.info("Email {} sent to {} ({})", content.template(), to, delivery.key());
        return Outcome.SENT;
    }

    /** {@code s***@example.com} — logs never hold whole addresses. */
    static String masked(EmailAddress address) {
        var value = address.address();
        var at = value.lastIndexOf('@');
        return value.charAt(0) + "***" + value.substring(at);
    }
}
