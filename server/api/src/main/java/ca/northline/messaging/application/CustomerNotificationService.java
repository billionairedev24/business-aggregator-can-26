package ca.northline.messaging.application;

import ca.northline.messaging.api.QuietHours;
import ca.northline.messaging.application.CustomerNotifications.Change;
import ca.northline.messaging.application.CustomerNotifications.CustomerPrefsStore;
import ca.northline.messaging.application.CustomerNotifications.ManageCustomerNotifications;
import ca.northline.messaging.domain.CustomerNotificationPrefs;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account › Notifications for customers (S-59). Stored only — no customer notification is sent by the worker yet
 * (DECISIONS S-59); the S-27 consumer will read these columns when customer events exist.
 */
@Service
@RequiredArgsConstructor
@Transactional
class CustomerNotificationService implements ManageCustomerNotifications, QuietHours {

    private final CustomerPrefsStore store;

    @Override
    @Transactional(readOnly = true)
    public CustomerNotificationPrefs view(String userId) {
        return store.find(userId).orElseGet(CustomerNotificationPrefs::defaultsOnly);
    }

    @Override
    public CustomerNotificationPrefs update(String userId, Change c) {
        var next = view(userId).edit(c.matrix(), c.quietOn(), c.quietFrom(), c.quietTo(), c.language(), c.marketing());
        store.save(userId, next);
        return next;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Window> of(String userId) {
        var prefs = view(userId);
        return prefs.quietOn() ? Optional.of(new Window(prefs.quietFrom(), prefs.quietTo())) : Optional.empty();
    }
}
