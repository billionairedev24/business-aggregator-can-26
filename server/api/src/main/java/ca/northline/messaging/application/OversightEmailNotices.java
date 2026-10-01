package ca.northline.messaging.application;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailContent.SellerOversightNotice;
import ca.northline.email.EmailContent.SellerOversightNotice.Action;
import ca.northline.email.EmailDeliveryFailed;
import ca.northline.email.Mailer;
import ca.northline.identity.api.NotificationContacts;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.merchants.api.MerchantReinstated;
import ca.northline.merchants.api.MerchantSearchVisibilityChanged;
import ca.northline.merchants.api.MerchantSuspended;
import ca.northline.merchants.api.MerchantTierChanged;
import ca.northline.merchants.api.ReverificationRequired;
import ca.northline.merchants.api.SellerDirectory;
import ca.northline.merchants.api.TeamRoster;
import ca.northline.shared.security.MerchantRole;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * S-82: emails the business's owners when Northline staff act on it from the console (suspended, reinstated, a check
 * asked for again, tier changed), with the reason the staff member gave. An account notice: always sent, in each
 * owner's language, at most once per (event, owner); a provider outage fails the listener so the publication is
 * retried.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OversightEmailNotices {

    private final TeamRoster roster;
    private final NotificationContacts contacts;
    private final BusinessNames businesses;
    private final SellerDirectory directory;
    private final Mailer mailer;
    private final NotificationLinks links;

    @ApplicationModuleListener
    void on(MerchantSuspended e) {
        send(e.eventId(), e.aggregateId(), e.actionId(), Action.SUSPENDED, null, null, null, "help");
    }

    @ApplicationModuleListener
    void on(MerchantReinstated e) {
        send(e.eventId(), e.aggregateId(), e.actionId(), Action.REINSTATED, null, null, null, "");
    }

    @ApplicationModuleListener
    void on(MerchantTierChanged e) {
        send(e.eventId(), e.aggregateId(), e.actionId(), Action.TIER_CHANGED, e.fromTier(), e.toTier(), null, "help");
    }

    @ApplicationModuleListener
    void on(MerchantSearchVisibilityChanged e) {
        send(
                e.eventId(),
                e.aggregateId(),
                e.actionId(),
                e.hidden() ? Action.SEARCH_HIDDEN : Action.SEARCH_RESTORED,
                null,
                null,
                null,
                e.hidden() ? "reviews" : "");
    }

    @ApplicationModuleListener
    void on(ReverificationRequired e) {
        send(
                e.eventId(),
                e.aggregateId(),
                e.actionId(),
                Action.REVERIFICATION_REQUIRED,
                null,
                null,
                e.checkType(),
                "compliance");
    }

    private void send(
            String eventId,
            String merchantId,
            String actionId,
            Action action,
            @Nullable String fromTier,
            @Nullable String toTier,
            @Nullable String checkType,
            String page) {
        var reason = directory
                .action(actionId)
                .map(SellerDirectory.Oversight::reason)
                .orElse("");
        var business = businesses.displayName(merchantId).orElse("Northline");
        var link = links.studio(merchantId, page);
        var owners = roster.members(merchantId).stream()
                .filter(m -> m.role() == MerchantRole.OWNER)
                .map(TeamRoster.TeamMember::userId)
                .distinct()
                .toList();
        Map<String, NotificationContacts.Contact> people = contacts.contacts(owners);
        EmailDeliveryFailed outage = null;
        for (var userId : owners) {
            var person = people.get(userId);
            if (person == null || person.email() == null || person.email().isBlank()) {
                continue;
            }
            EmailAddress to;
            try {
                to = new EmailAddress(person.email(), person.displayName());
            } catch (IllegalArgumentException ex) {
                log.warn("Owner {} of {} has an unusable email address; skipping {}", userId, merchantId, eventId);
                continue;
            }
            try {
                mailer.send(new Mailer.Delivery(
                        eventId + ":" + userId,
                        to,
                        new SellerOversightNotice(business, action, reason, fromTier, toTier, checkType, link),
                        person.locale(),
                        null));
            } catch (EmailDeliveryFailed ex) {
                outage = ex;
            }
        }
        if (outage != null) {
            throw outage;
        }
    }
}
