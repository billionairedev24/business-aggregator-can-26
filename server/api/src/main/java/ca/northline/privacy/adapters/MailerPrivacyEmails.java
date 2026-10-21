package ca.northline.privacy.adapters;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailContent;
import ca.northline.email.Mailer;
import ca.northline.privacy.application.PrivacyEmails;
import ca.northline.privacy.application.PrivacySettings;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link PrivacyEmails} over the shared email library (S-13: {@code EMAIL_PROVIDER}, Mailpit locally): the
 * {@code privacy-code} and {@code account-deletion} templates, en/fr. The code is never logged.
 */
@Component
@RequiredArgsConstructor
class MailerPrivacyEmails implements PrivacyEmails {

    /** The consumer site's page where a deletion is followed and cancelled. */
    static final String DELETE_PAGE = "/account/delete";

    private final Mailer mailer;
    private final PrivacySettings settings;

    @Override
    public void code(String key, String email, String firstName, String code, long validMinutes, Locale locale) {
        mailer.send(new Mailer.Delivery(
                key,
                EmailAddress.of(email),
                new EmailContent.PrivacyCode(firstName, code, validMinutes, page()),
                locale,
                null));
    }

    @Override
    public void deletionRequested(String key, String email, String firstName, Instant startsAt, Locale locale) {
        mailer.send(new Mailer.Delivery(
                key,
                EmailAddress.of(email),
                new EmailContent.AccountDeletion(
                        firstName, EmailContent.AccountDeletion.Stage.REQUESTED, startsAt, page()),
                locale,
                null));
    }

    @Override
    public void deletionCompleted(String key, String email, Locale locale) {
        mailer.send(new Mailer.Delivery(
                key,
                EmailAddress.of(email),
                new EmailContent.AccountDeletion(
                        "",
                        EmailContent.AccountDeletion.Stage.COMPLETED,
                        Instant.EPOCH,
                        URI.create(settings.site() + "/")),
                locale,
                null));
    }

    private URI page() {
        return URI.create(settings.site() + DELETE_PAGE);
    }
}
