package ca.northline.messaging.application;

import ca.northline.messaging.api.QuietHours;
import ca.northline.messaging.application.Consents.ManageConsents;
import ca.northline.messaging.application.CustomerNotifications.Change;
import ca.northline.messaging.application.CustomerNotifications.CustomerPrefsStore;
import ca.northline.messaging.application.CustomerNotifications.ManageCustomerNotifications;
import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.CustomerNotificationPrefs;
import ca.northline.messaging.domain.NotificationMatrix;
import ca.northline.shared.RuleViolation;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account › Notifications for customers (S-59). The {@code offers} row and the marketing-email choice are the person's
 * CASL consents (S-108): read from the consent records, and a change records a grant or withdrawal — the stored matrix
 * cells of that row are never used, and {@code marketing} keeps only the frequency the person chose (weekly or rewards).
 */
@Service
@RequiredArgsConstructor
@Transactional
class CustomerNotificationService implements ManageCustomerNotifications, QuietHours {

    static final String OFFERS = "offers";

    private final CustomerPrefsStore store;
    private final ManageConsents consents;

    @Override
    @Transactional(readOnly = true)
    public CustomerNotificationPrefs view(String userId) {
        var stored = store.find(userId).orElseGet(CustomerNotificationPrefs::defaultsOnly);
        return stored.withConsents(consents.granted(userId));
    }

    @Override
    public CustomerNotificationPrefs update(String userId, Change c) {
        var stored = store.find(userId).orElseGet(CustomerNotificationPrefs::defaultsOnly);
        var cells = c.matrix() == null ? null : new LinkedHashMap<>(c.matrix());
        var offers = cells == null ? null : cells.remove(OFFERS);
        // validate everything (unknown rows, channels, choices) before any consent is recorded
        var next = stored.edit(cells, c.quietOn(), c.quietFrom(), c.quietTo(), c.language(), frequency(c.marketing()));
        if (offers != null && !CustomerNotificationPrefs.CHANNELS.containsAll(offers.keySet())) {
            throw RuleViolation.of("matrix", "allowed", NotificationMatrix.UNKNOWN);
        }
        var wanted = new EnumMap<ConsentCategory, Boolean>(ConsentCategory.class);
        if (offers != null) {
            offers.forEach((channel, on) -> wanted.put(ConsentCategory.ofChannel(channel), on));
        }
        if (c.marketing() != null) {
            wanted.put(ConsentCategory.MARKETING_EMAIL, !"none".equals(c.marketing()));
        }
        store.save(userId, next);
        var context = c.consent();
        wanted.forEach((category, on) -> consents.change(
                userId,
                new Consents.Change(
                        category,
                        on,
                        context.source(),
                        context.wordingVersions().get(category.channel()),
                        context.language(),
                        context.evidence())));
        return next.withConsents(consents.granted(userId));
    }

    /** "none" is a withdrawal, not a frequency: the chosen frequency stays for the next time. */
    private static @Nullable String frequency(@Nullable String marketing) {
        return "none".equals(marketing) ? null : marketing;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Window> of(String userId) {
        var prefs = store.find(userId).orElseGet(CustomerNotificationPrefs::defaultsOnly);
        return prefs.quietOn() ? Optional.of(new Window(prefs.quietFrom(), prefs.quietTo())) : Optional.empty();
    }
}
