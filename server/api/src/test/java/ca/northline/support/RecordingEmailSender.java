package ca.northline.support;

import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The {@link EmailSender} of every integration test (imported by {@link IntegrationTest}): keeps what would have been
 * sent. The context is shared, so assert on your own recipients ({@link #to}), never on the total.
 */
public class RecordingEmailSender implements EmailSender {

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

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }
}
