package ca.northline.messaging.application;

import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The api's unsubscribe tokens: the shared format of the email library ({@link ca.northline.email.UnsubscribeTokens},
 * also used by the worker's notifications since S-27) with {@code EMAIL_UNSUBSCRIBE_KEY} from
 * {@link NotificationLinks#unsubscribeKey()}.
 */
@Component
class UnsubscribeTokens {

    private final ca.northline.email.UnsubscribeTokens tokens;

    UnsubscribeTokens(NotificationLinks links, Environment environment) {
        tokens = ca.northline.email.UnsubscribeTokens.forEnvironment(links.unsubscribeKey(), environment);
    }

    String issue(String userId, String event, Locale locale) {
        return tokens.issue(userId, event, locale);
    }

    Optional<UnsubscribeFromEmails.Subscription> read(String token) {
        return tokens.read(token).map(s -> new UnsubscribeFromEmails.Subscription(s.userId(), s.row(), s.locale()));
    }
}
