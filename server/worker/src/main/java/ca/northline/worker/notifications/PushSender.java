package ca.northline.worker.notifications;

import java.net.URI;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: a push notification to a person's installations of one app. Adapters (S-102,
 * docs/runbooks/push.md): {@link #LOGGING} under {@code northline.push.provider=local}, and
 * {@code ca.northline.worker.push.DevicePushSender} under {@code native} — APNs for iOS installations, FCM for
 * Android ones, from the device registry. The matrix, quiet hours and once-per-recipient rules apply before this.
 */
public interface PushSender {

    /**
     * @return {@link Result#NO_DEVICE} when the person has no installation that allows notifications
     * @throws PushDeliveryFailed when the provider can't take it now (throttled, down): retried later
     */
    Result send(PushMessage message);

    enum Result {
        DELIVERED,
        NO_DEVICE
    }

    /**
     * One push, in both languages: each installation shows {@code language}'s words, or its own language's when the
     * person reads notifications in the app's language ({@code language} null).
     *
     * @param link the deep link (https on the consumer host; the app routes it), null = opens the app
     * @param data ids for the app — never names, addresses, phone numbers or emails
     */
    record PushMessage(
            String userId,
            PushApp app,
            Map<String, Content> content,
            @Nullable String language,
            @Nullable URI link,
            Map<String, String> data,
            String collapseKey) {

        public PushMessage {
            content = Map.copyOf(content);
            data = Map.copyOf(data);
        }

        /** The words for an installation whose own language is {@code deviceLanguage} ({@code en} | {@code fr}). */
        public Content in(String deviceLanguage) {
            var chosen = language != null ? language : deviceLanguage;
            var words = content.get(chosen);
            return words != null
                    ? words
                    : content.getOrDefault("en", content.values().iterator().next());
        }
    }

    record Content(String title, String body) {}

    PushSender LOGGING = new Logging();

    /** {@code northline.push.provider=local}: logs the title only (nothing leaves the worker). */
    @Slf4j
    final class Logging implements PushSender {
        @Override
        public Result send(PushMessage message) {
            log.info(
                    "PUSH (local — no push provider) to user {} ({} app): {}",
                    message.userId(),
                    message.app().code(),
                    message.in("en").title());
            return Result.DELIVERED;
        }
    }
}
