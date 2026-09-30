package ca.northline.support;

import ca.northline.sms.SmsTransport;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The {@link SmsTransport} of every integration test (imported by {@link IntegrationTest}): keeps the texts that would
 * have been sent. The context is shared, so assert on your own numbers ({@link #to}).
 */
public class RecordingSmsTransport implements SmsTransport {

    public record Text(String to, String body) {}

    private final List<Text> sent = new CopyOnWriteArrayList<>();

    @Override
    public String sendText(String to, String body) {
        sent.add(new Text(to, body));
        return "SM-test-" + sent.size();
    }

    @Override
    public String call(String to, String spokenText, Locale locale) {
        sent.add(new Text(to, spokenText));
        return "CA-test-" + sent.size();
    }

    public List<Text> to(String e164) {
        return sent.stream().filter(t -> t.to().equals(e164)).toList();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        RecordingSmsTransport recordingSmsTransport() {
            return new RecordingSmsTransport();
        }
    }
}
