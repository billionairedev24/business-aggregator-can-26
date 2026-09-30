package ca.northline.merchants.application;

import ca.northline.identity.api.NotificationContacts;
import ca.northline.merchants.domain.IdentityCheckStatus;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Emails an owner their Stripe Identity link after the request committed (S-22): async, own transaction, retried
 * from the event registry when the provider is down. A link whose session was replaced or already used is not sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class IdentityLinkDelivery {

    private final OwnerIdentityStore store;
    private final MerchantRepository merchants;
    private final NotificationContacts contacts;
    private final IdentityLinkSender sender;

    @ApplicationModuleListener
    void on(IdentityLinkRequested event) {
        var check = store.find(event.merchantId(), event.checkId()).orElse(null);
        if (check == null
                || !event.sessionId().equals(check.getStripeSession())
                || check.getStatus() != IdentityCheckStatus.PENDING
                || check.getEmail() == null) {
            log.info("Identity link {} is no longer current; not sent", event.checkId());
            return;
        }
        var owner = store.owners(event.merchantId()).stream()
                .filter(o -> o.principalId().equals(check.getPrincipalId()))
                .findFirst()
                .orElse(null);
        var merchant = merchants.findById(event.merchantId()).orElse(null);
        if (owner == null || merchant == null) {
            return;
        }
        var requester = contacts.contact(event.requesterId());
        sender.send(
                event.eventId(),
                new IdentityLinkSender.Link(
                        check.getEmail(),
                        owner.legalName(),
                        merchant.getDisplayName().value(),
                        requester.map(NotificationContacts.Contact::displayName).orElse(""),
                        event.url(),
                        requester.map(NotificationContacts.Contact::locale).orElse(Locale.CANADA)));
    }
}
