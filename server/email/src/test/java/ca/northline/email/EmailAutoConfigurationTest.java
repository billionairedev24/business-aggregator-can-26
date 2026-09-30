package ca.northline.email;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.email.azure.AzureEmailSender;
import ca.northline.email.sendgrid.SendGridEmailSender;
import ca.northline.email.ses.SesEmailSender;
import ca.northline.email.smtp.SmtpEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provider selection from {@code northline.email.provider}, missing settings, and {@code local} in production. */
class EmailAutoConfigurationTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EmailAutoConfiguration.class))
            .withSystemProperties("aws.accessKeyId=test-access-key", "aws.secretAccessKey=test-secret-key");

    @Test
    void defaultIsLocal_smtpToMailpit_withTheLibraryBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Mailer.class).hasSingleBean(EmailTemplates.class);
            assertThat(adapter(context.getBean(EmailSender.class))).isInstanceOf(SmtpEmailSender.class);
            assertThat(context.getBean(SentEmails.class)).isSameAs(SentEmails.NONE); // no DataSource here
        });
    }

    @Test
    void eachProviderGetsItsAdapter() {
        runner.withPropertyValues("northline.email.provider=smtp", "northline.email.smtp.host=smtp.example.test")
                .run(c -> assertThat(adapter(c.getBean(EmailSender.class))).isInstanceOf(SmtpEmailSender.class));
        runner.withPropertyValues("northline.email.provider=ses", "northline.email.region=ca-central-1")
                .run(c -> assertThat(adapter(c.getBean(EmailSender.class))).isInstanceOf(SesEmailSender.class));
        runner.withPropertyValues("northline.email.provider=sendgrid", "northline.email.api-key=SG.fake")
                .run(c -> assertThat(adapter(c.getBean(EmailSender.class))).isInstanceOf(SendGridEmailSender.class));
        runner.withPropertyValues(
                        "northline.email.provider=azure",
                        "northline.email.endpoint=https://nl.communication.azure.com",
                        "northline.email.api-key=ZmFrZQ==")
                .run(c -> assertThat(adapter(c.getBean(EmailSender.class))).isInstanceOf(AzureEmailSender.class));
    }

    @Test
    void missingSettings_areAllNamed() {
        runner.withPropertyValues("northline.email.provider=sendgrid")
                .run(c -> assertThat(c)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("EMAIL_PROVIDER=sendgrid needs: EMAIL_API_KEY"));
        runner.withPropertyValues("northline.email.provider=azure")
                .run(c -> assertThat(c).getFailure().rootCause().hasMessageContaining("EMAIL_ENDPOINT"));
        runner.withPropertyValues(
                        "northline.email.provider=smtp",
                        "northline.email.smtp.host=smtp.example.test",
                        "northline.email.smtp.username=relay")
                .run(c -> assertThat(c)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("SMTP_STARTTLS=true")
                        .hasMessageContaining("SMTP_PASSWORD"));
        runner.withPropertyValues("northline.email.from=not an address")
                .run(c -> assertThat(c).getFailure().hasStackTraceContaining("EMAIL_FROM is not an email address"));
    }

    @Test
    void local_isRefusedUnderStagingAndProd_allowedUnderDev() {
        for (var profile : new String[] {"staging", "prod"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(c -> assertThat(c)
                            .getFailure()
                            .rootCause()
                            .hasMessageContaining("EMAIL_PROVIDER=local is not allowed under staging/prod"));
        }
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(c -> assertThat(c).hasNotFailed());
    }

    @Test
    void anApplicationSender_replacesTheAdapter() {
        runner.withUserConfiguration(OwnSender.class)
                .run(c -> assertThat(c.getBean(EmailSender.class)).isSameAs(c.getBean(OwnSender.class).sender));
    }

    private static EmailSender adapter(EmailSender bean) {
        return ((RetryingEmailSender) bean).delegate();
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnSender {
        final EmailSender sender = _ -> {};

        @Bean
        EmailSender emailSender() {
            return sender;
        }
    }
}
