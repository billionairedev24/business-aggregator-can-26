package ca.northline.email;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Retries {@link EmailDeliveryFailed.Kind#UNAVAILABLE} failures with exponential back-off (the adapters themselves
 * never retry, so there is one policy for every provider). Rejections are never retried. Closes the adapter's client.
 */
@Slf4j
final class RetryingEmailSender implements EmailSender, AutoCloseable {

    private final EmailSender delegate;
    private final int attempts;
    private final Duration backoff;

    RetryingEmailSender(EmailSender delegate, int attempts, Duration backoff) {
        if (attempts < 1 || backoff.isNegative()) {
            throw new IllegalArgumentException("EMAIL_RETRY_ATTEMPTS must be ≥ 1 and EMAIL_RETRY_BACKOFF ≥ 0");
        }
        this.delegate = delegate;
        this.attempts = attempts;
        this.backoff = backoff;
    }

    @Override
    public void send(EmailMessage message) {
        var wait = backoff;
        for (int attempt = 1; ; attempt++) {
            try {
                delegate.send(message);
                return;
            } catch (EmailDeliveryFailed e) {
                if (e.getKind() == EmailDeliveryFailed.Kind.REJECTED || attempt >= attempts) {
                    throw e;
                }
                log.info(
                        "Email '{}' not accepted (attempt {}/{}): {} — retrying in {}",
                        message.tag(),
                        attempt,
                        attempts,
                        e.getMessage(),
                        wait);
                sleep(wait);
                wait = wait.multipliedBy(2);
            }
        }
    }

    EmailSender delegate() {
        return delegate;
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw EmailDeliveryFailed.unavailable("Interrupted while waiting to retry", e);
        }
    }
}
