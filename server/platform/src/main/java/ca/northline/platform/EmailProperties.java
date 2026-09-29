package ca.northline.platform;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.email.*} — transactional email. Only {@code local} exists today (messages are written to the log);
 * SMTP (Mailpit locally), Amazon SES, SendGrid and Azure Communication Services adapters come with backlog story S-13.
 *
 * @param provider {@code local | smtp | ses | sendgrid | azure} ({@code EMAIL_PROVIDER})
 * @param from sender address ({@code EMAIL_FROM})
 * @param smtp SMTP server when {@code provider = smtp}
 */
@ConfigurationProperties("northline.email")
public record EmailProperties(
        @DefaultValue("local") Provider provider,
        @DefaultValue("Northline <no-reply@northline.ca>") String from,
        @DefaultValue Smtp smtp) {

    /** Which adapter sends email. */
    public enum Provider {
        LOCAL,
        SMTP,
        SES,
        SENDGRID,
        AZURE
    }

    /**
     * @param host {@code SMTP_HOST}
     * @param port {@code SMTP_PORT} (Mailpit: 1025)
     * @param username {@code SMTP_USERNAME}
     * @param password {@code SMTP_PASSWORD}
     * @param starttls {@code SMTP_STARTTLS}
     */
    public record Smtp(
            @DefaultValue("localhost") String host,
            @DefaultValue("1025") int port,
            @Nullable String username,
            @Nullable String password,
            @DefaultValue("false") boolean starttls) {}
}
