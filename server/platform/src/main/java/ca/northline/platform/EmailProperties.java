package ca.northline.platform;

import java.time.Duration;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.email.*} — transactional email (S-13, library {@code server/email}, package {@code
 * ca.northline.email}). {@code staging}/{@code prod} refuse {@code local}. See docs/runbooks/README.md § Email.
 *
 * @param provider {@code local} (SMTP to Mailpit; a missing Mailpit only logs) | {@code smtp} (any SMTP relay,
 *     including Amazon SES SMTP) | {@code ses} (Amazon SES API v2) | {@code sendgrid} (Twilio SendGrid v3 API) |
 *     {@code azure} (Azure Communication Services Email) ({@code EMAIL_PROVIDER})
 * @param from sender, {@code Name <address>}; its domain must be verified at the provider ({@code EMAIL_FROM})
 * @param replyTo where replies go; empty = no Reply-To header ({@code EMAIL_REPLY_TO})
 * @param mailingAddress CASL sender identification printed in every footer: the legal entity's address, configuration
 *     only — no default in code ({@code EMAIL_MAILING_ADDRESS}, required)
 * @param timeZone the zone dates and times in emails are written in ({@code EMAIL_TIME_ZONE}, else the platform zone
 *     {@code REGION_PLATFORM_ZONE}; required)
 * @param contact CASL contact line printed in every footer (an email address or web page) ({@code EMAIL_CONTACT})
 * @param region AWS region for {@code ses}, e.g. {@code ca-central-1}; empty = the SDK default chain ({@code
 *     EMAIL_REGION})
 * @param endpoint API base override: the Azure Communication Services resource endpoint ({@code
 *     https://<resource>.communication.azure.com}, required for {@code azure}), a mock in tests, a VPC endpoint
 *     ({@code EMAIL_ENDPOINT})
 * @param apiKey SendGrid API key ({@code SG.…}) or the Azure Communication Services access key (empty = Entra ID
 *     workload identity) ({@code EMAIL_API_KEY})
 * @param configurationSet SES configuration set (event publishing, dedicated IPs); empty = none ({@code
 *     EMAIL_CONFIGURATION_SET})
 * @param smtp SMTP server when {@code provider = local | smtp}
 * @param retry retries of transient failures (5xx, 429, time-outs, connection refused) before the send fails
 */
@ConfigurationProperties("northline.email")
public record EmailProperties(
        @DefaultValue("local") Provider provider,
        @DefaultValue("Northline <no-reply@northline.ca>") String from,
        @Nullable String replyTo,

        @Nullable String mailingAddress,

        @DefaultValue("support@northline.ca") String contact,
        @Nullable ZoneId timeZone,
        @Nullable String region,
        @Nullable String endpoint,
        @Nullable String apiKey,
        @Nullable String configurationSet,
        @DefaultValue Smtp smtp,
        @DefaultValue Retry retry) {

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
     * @param port {@code SMTP_PORT} (Mailpit: 1025; relays: 587 with STARTTLS)
     * @param username {@code SMTP_USERNAME}
     * @param password {@code SMTP_PASSWORD}
     * @param starttls {@code SMTP_STARTTLS} (required when a username is set, except under {@code local})
     */
    public record Smtp(
            @DefaultValue("localhost") String host,
            @DefaultValue("1025") int port,
            @Nullable String username,
            @Nullable String password,
            @DefaultValue("false") boolean starttls) {}

    /**
     * @param attempts tries per send, the first included ({@code EMAIL_RETRY_ATTEMPTS})
     * @param backoff wait before the second try; doubled for each further one ({@code EMAIL_RETRY_BACKOFF})
     */
    public record Retry(
            @DefaultValue("3") int attempts,
            @DefaultValue("2s") Duration backoff) {}
}
