package ca.northline.email;

import ca.northline.email.azure.AzureEmailSender;
import ca.northline.email.sendgrid.SendGridEmailSender;
import ca.northline.email.ses.SesEmailSender;
import ca.northline.email.smtp.SmtpEmailSender;
import ca.northline.platform.EmailProperties;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Wires the email library from {@code northline.email.*} ({@link EmailProperties}): exactly one provider adapter,
 * wrapped in the retry policy, the templates and the {@link Mailer}. A provider with missing settings stops start-up
 * naming every missing variable; {@code staging}/{@code prod} refuse {@code local} (their required-env also lists
 * {@code EMAIL_PROVIDER} and {@code EMAIL_FROM}). Applications or tests may declare their own {@link EmailSender}.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailAutoConfiguration {

    static final String RUNBOOK = "docs/runbooks/email.md";

    @Bean
    @ConditionalOnMissingBean
    EmailTemplates emailTemplates(EmailProperties email) {
        var address = email.mailingAddress();
        var zone = email.timeZone();
        if (address == null || address.isBlank()) {
            throw new IllegalStateException(
                    "EMAIL_MAILING_ADDRESS is required: every email names the sender's mailing address (CASL; "
                            + RUNBOOK + ")");
        }
        if (zone == null) {
            throw new IllegalStateException(
                    "northline.email.time-zone (EMAIL_TIME_ZONE, else REGION_PLATFORM_ZONE) is required (" + RUNBOOK
                            + ")");
        }
        return new EmailTemplates(address, email.contact(), zone);
    }

    @Bean
    @ConditionalOnMissingBean
    EmailSender emailSender(EmailProperties email, Environment environment, ObjectProvider<Clock> clock) {
        return sender(email, environment, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    SentEmails sentEmails(ObjectProvider<DataSource> dataSource, ObjectProvider<PlatformTransactionManager> tx) {
        var ds = dataSource.getIfAvailable();
        if (ds == null) {
            return SentEmails.NONE;
        }
        var manager = tx.getIfAvailable();
        TransactionTemplate separately = null;
        if (manager != null) {
            separately = new TransactionTemplate(manager);
            separately.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        return new JdbcSentEmails(JdbcClient.create(ds), separately);
    }

    @Bean
    @ConditionalOnMissingBean
    Mailer mailer(EmailSender sender, EmailTemplates templates, SentEmails sent) {
        return new DefaultMailer(sender, templates, sent);
    }

    /** The configured adapter behind the retry policy — exactly what the bean is (contract tests use it). */
    static EmailSender sender(EmailProperties email, Environment environment, Clock clock) {
        var from = mailbox(email.from(), "EMAIL_FROM");
        var replyTo = StringUtils.hasText(email.replyTo()) ? mailbox(email.replyTo(), "EMAIL_REPLY_TO") : null;
        var provider = email.provider().name().toLowerCase(Locale.ROOT);
        EmailSender adapter = switch (email.provider()) {
            case LOCAL -> local(email, environment, from, replyTo);
            case SMTP -> smtp(email, from, replyTo);
            case SES ->
                new SesEmailSender(
                        SesEmailSender.client(email.region(), email.endpoint()),
                        from,
                        replyTo,
                        email.configurationSet());
            case SENDGRID -> {
                var problems = new ArrayList<String>();
                var key =
                        require(email.apiKey(), "EMAIL_API_KEY (SendGrid API key with Mail Send permission)", problems);
                fail(provider, problems);
                yield new SendGridEmailSender(SendGridEmailSender.client(email.endpoint(), key), from, replyTo);
            }
            case AZURE -> {
                var problems = new ArrayList<String>();
                var endpoint = require(
                        email.endpoint(), "EMAIL_ENDPOINT (https://<resource>.communication.azure.com)", problems);
                fail(provider, problems);
                yield new AzureEmailSender(AzureEmailSender.client(endpoint, email.apiKey(), clock), from, replyTo);
            }
        };
        log.info(
                "Email: provider={} from={} retry={}×{}{}",
                provider,
                from.address(),
                email.retry().attempts(),
                email.retry().backoff(),
                email.provider() == EmailProperties.Provider.SES ? " region=" + email.region() : "");
        return new RetryingEmailSender(
                adapter, email.retry().attempts(), email.retry().backoff());
    }

    private static EmailSender local(
            EmailProperties email, Environment environment, EmailAddress from, @Nullable EmailAddress replyTo) {
        if (environment.matchesProfiles("staging | prod")) {
            throw new IllegalStateException("EMAIL_PROVIDER=local is not allowed under staging/prod: set EMAIL_PROVIDER"
                    + " to smtp, ses, sendgrid or azure (" + RUNBOOK + ")");
        }
        if (environment.matchesProfiles("dev")) {
            log.warn(
                    "EMAIL_PROVIDER=local: email goes to the SMTP server at {}:{} (Mailpit) or only to the log",
                    email.smtp().host(),
                    email.smtp().port());
        }
        var smtp = email.smtp();
        return new SmtpEmailSender(
                SmtpEmailSender.client(smtp.host(), smtp.port(), smtp.username(), smtp.password(), smtp.starttls()),
                from,
                replyTo,
                true);
    }

    private static EmailSender smtp(EmailProperties email, EmailAddress from, @Nullable EmailAddress replyTo) {
        var smtp = email.smtp();
        var problems = new ArrayList<String>();
        require(smtp.host(), "SMTP_HOST", problems);
        if (StringUtils.hasText(smtp.username()) && !smtp.starttls()) {
            problems.add("SMTP_STARTTLS=true (credentials are never sent over a plain connection)");
        }
        if (StringUtils.hasText(smtp.username()) && !StringUtils.hasText(smtp.password())) {
            problems.add("SMTP_PASSWORD");
        }
        fail("smtp", problems);
        return new SmtpEmailSender(
                SmtpEmailSender.client(smtp.host(), smtp.port(), smtp.username(), smtp.password(), smtp.starttls()),
                from,
                replyTo,
                false);
    }

    private static EmailAddress mailbox(String value, String variable) {
        try {
            return EmailAddress.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    variable + " is not an email address: '" + value + "' (" + RUNBOOK + ")", e);
        }
    }

    private static String require(@Nullable String value, String what, List<String> problems) {
        if (!StringUtils.hasText(value)) {
            problems.add(what);
            return "";
        }
        return value.strip();
    }

    private static void fail(String provider, List<String> problems) {
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "EMAIL_PROVIDER=" + provider + " needs: " + String.join("; ", problems) + " (" + RUNBOOK + ")");
        }
    }
}
