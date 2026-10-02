package ca.northline.worker.support;

import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import ca.northline.sms.SmsDeliveryFailed;
import ca.northline.sms.SmsTransport;
import ca.northline.worker.notifications.PushApp;
import ca.northline.worker.notifications.PushSender;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Providers of the worker's integration tests: recording email, SMS and push, and a clock the tests move. */
@TestConfiguration(proxyBeanMethods = false)
public class NotificationTestBeans {

    public static final Instant NOON_IN_EDMONTON = Instant.parse("2026-09-30T18:00:00Z");

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(NOON_IN_EDMONTON);
    }

    @Bean
    Emails recordingEmailSender() {
        return new Emails();
    }

    @Bean
    @Primary
    Texts recordingSmsTransport() {
        return new Texts();
    }

    /** Recorded instead of sent, unless a test runs the real APNs/FCM adapters ({@code northline.push.provider=native}). */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "northline.push.provider", havingValue = "local", matchIfMissing = true)
    Pushes recordingPushSender() {
        return new Pushes();
    }

    public static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        public void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public static final class Emails implements EmailSender {
        private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(EmailMessage message) {
            sent.add(message);
        }

        public List<EmailMessage> to(String address) {
            return sent.stream()
                    .filter(m -> m.to().address().equalsIgnoreCase(address))
                    .toList();
        }
    }

    public static final class Texts implements SmsTransport {
        public record Text(String to, String body) {}

        private final List<Text> sent = new CopyOnWriteArrayList<>();
        private final Map<String, Failure> failures = new ConcurrentHashMap<>();

        private record Failure(SmsDeliveryFailed.Kind kind, AtomicInteger times) {}

        /** The next {@code times} texts to {@code number} fail with {@code kind}. */
        public void fail(String number, SmsDeliveryFailed.Kind kind, int times) {
            failures.put(number, new Failure(kind, new AtomicInteger(times)));
        }

        public AtomicInteger attempts = new AtomicInteger();

        @Override
        public String sendText(String to, String body) {
            attempts.incrementAndGet();
            var failure = failures.get(to);
            if (failure != null && failure.times().getAndDecrement() > 0) {
                throw new SmsDeliveryFailed(failure.kind(), "simulated " + failure.kind());
            }
            sent.add(new Text(to, body));
            return "SM" + sent.size();
        }

        @Override
        public String call(String to, String spokenText, Locale locale) {
            throw new UnsupportedOperationException("notifications never call");
        }

        public List<Text> to(String number) {
            return sent.stream().filter(t -> t.to().equals(number)).toList();
        }
    }

    public static final class Pushes implements PushSender {
        /** One push as the person's phone would show it (their language, or English when they follow the app). */
        public record Push(
                String userId,
                PushApp app,
                String title,
                String body,
                @Nullable URI link,
                Map<String, String> data,
                @Nullable String language) {}

        private final List<Push> sent = new CopyOnWriteArrayList<>();

        @Override
        public Result send(PushMessage message) {
            var words = message.in("en"); // an installation in English when the person follows the app
            sent.add(new Push(
                    message.userId(),
                    message.app(),
                    words.title(),
                    words.body(),
                    message.link(),
                    message.data(),
                    message.language()));
            return Result.DELIVERED;
        }

        public List<Push> to(String userId) {
            return sent.stream().filter(p -> p.userId().equals(userId)).toList();
        }
    }
}
