package ca.northline.messaging.application;

import ca.northline.messaging.application.NotificationPreferences.NotificationPrefsStore;
import ca.northline.messaging.domain.NotificationMatrix;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Unsubscribe links (CASL): a valid token turns one email cell of the member's matrix off. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class EmailUnsubscribeService implements UnsubscribeFromEmails {

    private final UnsubscribeTokens tokens;
    private final NotificationPrefsStore store;

    @Override
    public Optional<Subscription> check(String token) {
        return tokens.read(token).filter(s -> NotificationMatrix.events().contains(s.event()));
    }

    @Override
    @Transactional
    public Optional<Subscription> unsubscribe(String token) {
        return check(token).map(subscription -> {
            var current = store.find(subscription.userId()).orElseGet(NotificationMatrix::defaultsOnly);
            if (current.wants(subscription.event(), "email")) {
                store.save(
                        subscription.userId(),
                        NotificationMatrix.edit(Map.of(subscription.event(), Map.of("email", false)), current));
                log.info(
                        "Email '{}' turned off for {} by an unsubscribe link",
                        subscription.event(),
                        subscription.userId());
            }
            return subscription;
        });
    }
}
