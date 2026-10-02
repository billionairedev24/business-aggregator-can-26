package ca.northline.messaging.application;

import ca.northline.messaging.application.Consents.ManageConsents;
import ca.northline.messaging.application.CustomerNotifications.ManageCustomerNotifications;
import ca.northline.messaging.application.NotificationPreferences.NotificationPrefsStore;
import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.ConsentEvidence;
import ca.northline.messaging.domain.ConsentSource;
import ca.northline.messaging.domain.CustomerNotificationPrefs;
import ca.northline.messaging.domain.NotificationMatrix;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unsubscribe links (CASL): a valid token turns one email cell of the member's matrix off — or, for a customer
 * notification of the worker (S-102, row {@code customer.<event>}), one cell of the customer's matrix. A commercial
 * message's token (S-108: {@code consent.<category>}, and the customer's {@code customer.offers}) withdraws the
 * consent at once — CASL allows ten business days; Northline does it in the same request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class EmailUnsubscribeService implements UnsubscribeFromEmails {

    private final UnsubscribeTokens tokens;
    static final String CUSTOMER = "customer.";

    private final NotificationPrefsStore store;
    private final ManageCustomerNotifications customers;
    private final ManageConsents consents;

    @Override
    public Optional<Subscription> check(String token) {
        return tokens.read(token)
                .filter(s -> NotificationMatrix.events().contains(s.event())
                        || customerRow(s.event())
                        || consent(s.event()) != null);
    }

    @Override
    @Transactional
    public Optional<Subscription> unsubscribe(String token, boolean oneClick) {
        return check(token).map(subscription -> {
            var event = subscription.event();
            var category = event.equals(CUSTOMER + CustomerNotificationPrefs.OFFERS)
                    ? ConsentCategory.MARKETING_EMAIL
                    : consent(event);
            if (category != null) {
                consents.change(
                        subscription.userId(),
                        new Consents.Change(
                                category,
                                false,
                                oneClick ? ConsentSource.LIST_UNSUBSCRIBE : ConsentSource.UNSUBSCRIBE_LINK,
                                null,
                                subscription.locale().getLanguage(),
                                ConsentEvidence.NONE));
                return subscription;
            }
            if (customerRow(event)) {
                var row = event.substring(CUSTOMER.length());
                var cells = customers.view(subscription.userId()).matrix().getOrDefault(row, Map.of());
                if (Boolean.TRUE.equals(cells.get("email"))) {
                    customers.update(
                            subscription.userId(),
                            new CustomerNotifications.Change(
                                    Map.of(row, Map.of("email", false)), null, null, null, null, null));
                    log.info(
                            "Customer email '{}' turned off for {} by an unsubscribe link", row, subscription.userId());
                }
                return subscription;
            }
            var current = store.find(subscription.userId()).orElseGet(NotificationMatrix::defaultsOnly);
            if (current.wants(event, "email")) {
                store.save(
                        subscription.userId(), NotificationMatrix.edit(Map.of(event, Map.of("email", false)), current));
                log.info("Email '{}' turned off for {} by an unsubscribe link", event, subscription.userId());
            }
            return subscription;
        });
    }

    private static boolean customerRow(String event) {
        return event.startsWith(CUSTOMER)
                && !event.equals(CUSTOMER + CustomerNotificationPrefs.SECURITY)
                && CustomerNotificationPrefs.events().contains(event.substring(CUSTOMER.length()));
    }

    private static @Nullable ConsentCategory consent(String event) {
        if (!event.startsWith(CONSENT)) {
            return null;
        }
        var code = event.substring(CONSENT.length());
        return Arrays.stream(ConsentCategory.values())
                .filter(c -> c.code().equals(code))
                .findFirst()
                .orElse(null);
    }
}
