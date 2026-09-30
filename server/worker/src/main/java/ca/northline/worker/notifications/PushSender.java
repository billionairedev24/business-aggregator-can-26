package ca.northline.worker.notifications;

import lombok.extern.slf4j.Slf4j;

/**
 * Outbound port: a push notification to a member's devices. <b>Stub</b> — Northline has no push provider and no device
 * registration yet (no app ships push); {@link #LOGGING} records what would be sent. The matrix, quiet hours and
 * once-per-recipient rules already apply, so a real adapter (FCM/APNs through one provider) only replaces this bean.
 */
public interface PushSender {

    /** @throws RuntimeException when the provider can't take it now (retried) */
    void send(String userId, String title, String body);

    PushSender LOGGING = new Logging();

    /** The stub: logs the title only (no provider, nothing leaves the worker). */
    @Slf4j
    final class Logging implements PushSender {
        @Override
        public void send(String userId, String title, String body) {
            log.info("PUSH (stub — no push provider yet) to user {}: {}", userId, title);
        }
    }
}
