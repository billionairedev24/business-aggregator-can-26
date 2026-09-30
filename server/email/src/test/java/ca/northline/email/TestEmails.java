package ca.northline.email;

import ca.northline.platform.EmailProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.mock.env.MockEnvironment;

/** Settings and messages shared by the library tests. Fake credentials only. */
final class TestEmails {

    static final String FROM = "Northline <no-reply@mail.northline.test>";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T18:00:00Z"), ZoneOffset.UTC);

    private TestEmails() {}

    static EmailProperties props(
            EmailProperties.Provider provider,
            @Nullable String endpoint,
            @Nullable String apiKey,
            EmailProperties.Smtp smtp) {
        return new EmailProperties(
                provider,
                FROM,
                "support@northline.test",
                "Northline Marketplace Inc. · 1200 – 8th Avenue SW, Calgary, Alberta T2P 1B5, Canada",
                "support@northline.ca",
                "ca-central-1",
                endpoint,
                apiKey,
                null,
                smtp,
                new EmailProperties.Retry(3, Duration.ofMillis(1)));
    }

    static EmailProperties props(
            EmailProperties.Provider provider, @Nullable String endpoint, @Nullable String apiKey) {
        return props(provider, endpoint, apiKey, new EmailProperties.Smtp("localhost", 1025, null, null, false));
    }

    static EmailSender sender(EmailProperties props) {
        return EmailAutoConfiguration.sender(props, new MockEnvironment(), CLOCK);
    }

    static EmailMessage message() {
        return new EmailMessage(
                new EmailAddress("sam@example.com", "Sam Lée"),
                "Rejoignez Prairie Wrench sur Northline",
                "<p>Bonjour</p>",
                "Bonjour",
                Map.of(
                        EmailMessage.LIST_UNSUBSCRIBE,
                        "<http://localhost:8080/api/v1/email/unsubscribe?t=abc>",
                        EmailMessage.LIST_UNSUBSCRIBE_POST,
                        "List-Unsubscribe=One-Click"),
                "team-invitation");
    }
}
