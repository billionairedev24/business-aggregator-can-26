package ca.northline.auth.email;

import ca.northline.auth.application.AccountEmails;
import ca.northline.auth.application.AccountProperties;
import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.domain.App;
import ca.northline.email.EmailAddress;
import ca.northline.email.EmailContent;
import ca.northline.email.Mailer;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link AccountEmails} over the {@link Mailer}: one email per key, in the person's language, sent after the
 * transaction commits (a rolled-back registration sends nothing) on a virtual thread (the request's answer doesn't wait
 * for the provider, so its timing says nothing about whether an email went out). A failure is logged, never thrown:
 * the person asks for a new code. Links open the app the person came from: its origin is the one of its sign-in page
 * ({@code northline.auth.login-page}, {@code consumer-login-page}, {@code console-login-page}).
 */
@Slf4j
@Component
class MailerAccountEmails implements AccountEmails {

    private final Mailer mailer;
    private final AuthProperties props;
    private final TaskExecutor executor;

    MailerAccountEmails(
            Mailer mailer,
            AuthProperties props,
            AccountProperties accounts,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.mailer = mailer;
        this.props = props;
        this.executor = accounts.sendAsync() ? executor : Runnable::run;
    }

    @Override
    public void verification(Recipient to, String code, String token, long validMinutes) {
        send(
                to,
                new EmailContent.AccountVerification(
                        to.firstName(),
                        code,
                        link(to.app(), "/verify-email?token=" + encode(token)),
                        validMinutes,
                        to.app().settingsPlace()));
    }

    @Override
    public void passwordReset(Recipient to, String code, String token, long validMinutes) {
        send(
                to,
                new EmailContent.PasswordReset(
                        to.firstName(),
                        code,
                        link(to.app(), "/reset-password?token=" + encode(token)),
                        validMinutes,
                        to.app().settingsPlace()));
    }

    @Override
    public void accountExists(Recipient to) {
        send(
                to,
                new EmailContent.AccountExists(
                        to.firstName(),
                        link(to.app(), "/sign-in"),
                        link(to.app(), "/forgot-password"),
                        to.app().settingsPlace()));
    }

    /** The app's origin + path. */
    URI link(App app, String path) {
        var page = switch (app) {
            case STUDIO -> props.loginPage();
            case CONSUMER -> Objects.requireNonNullElse(props.consumerLoginPage(), props.loginPage());
            case CONSOLE -> Objects.requireNonNullElse(props.consoleLoginPage(), props.loginPage());
        };
        var base = URI.create(page.isBlank() ? props.loginPage() : page);
        var port = base.getPort() < 0 ? "" : ":" + base.getPort();
        return URI.create(base.getScheme() + "://" + base.getHost() + port + path);
    }

    private void send(Recipient to, EmailContent content) {
        var delivery = new Mailer.Delivery(
                to.key(), EmailAddress.of(to.to()), content, Locale.forLanguageTag(to.locale()), null);
        Runnable task = () -> {
            try {
                mailer.send(delivery);
            } catch (RuntimeException e) {
                log.warn("Account email {} not sent ({}): {}", content.template(), to.key(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
