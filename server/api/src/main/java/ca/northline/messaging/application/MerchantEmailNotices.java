package ca.northline.messaging.application;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailContent;
import ca.northline.email.EmailContent.ApplicationDecision;
import ca.northline.email.EmailContent.BankAccountChange;
import ca.northline.email.EmailContent.CustomDomainNotice;
import ca.northline.email.EmailContent.DisputeUpdate;
import ca.northline.email.EmailContent.ListingRejected;
import ca.northline.email.EmailContent.PayoutSent;
import ca.northline.email.EmailContent.RefundCaseUpdate;
import ca.northline.email.EmailContent.TrustWarning;
import ca.northline.email.EmailDeliveryFailed;
import ca.northline.email.Mailer;
import ca.northline.identity.api.NotificationContacts;
import ca.northline.merchants.api.ApplicationDecided;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.merchants.api.CustomDomainChanged;
import ca.northline.merchants.api.TeamRoster;
import ca.northline.messaging.api.ListingRejectedNotice;
import ca.northline.messaging.api.TrustWarningNotice;
import ca.northline.messaging.application.NotificationPreferences.NotificationPrefsStore;
import ca.northline.messaging.domain.NotificationMatrix;
import ca.northline.payments.api.DisputeDecided;
import ca.northline.payments.api.DisputeUpdated;
import ca.northline.payments.api.PayoutAccountChanged;
import ca.northline.payments.api.RefundCaseUpdated;
import ca.northline.payments.api.RefundIssued;
import ca.northline.shared.security.MerchantRole;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * S-13: emails the business's team about money events (and, S-31, its owners about the page's own domain), after the business transaction committed (Modulith
 * {@code @ApplicationModuleListener}: async, own transaction, publication kept until it completes). Each (event,
 * member) is emailed at most once ({@link Mailer} de-duplicates on {@code <eventId>:<userId>}), in the member's
 * language, and only if their Settings › Notifications email cell for that row is on — except the bank-account notice,
 * a security message that always goes out. A provider outage makes the listener fail so the publication is resubmitted
 * later; nothing here can fail the user's action.
 *
 * <p>SMS (the bank-change text the design promises) is not sent here: the SMS port lives in northline-auth, so the
 * S-27 notifications consumer sends it from {@code payments.payout_account} (docs/DECISIONS.md § S-13).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(NotificationLinks.class)
class MerchantEmailNotices {

    private static final Set<MerchantRole> OWNERS = Set.of(MerchantRole.OWNER);
    private static final Set<MerchantRole> FINANCE = Set.of(MerchantRole.OWNER, MerchantRole.BOOKKEEPER);

    private final TeamRoster roster;
    private final NotificationContacts contacts;
    private final BusinessNames businesses;
    private final NotificationPrefsStore prefs;
    private final Mailer mailer;
    private final NotificationLinks links;
    private final UnsubscribeTokens tokens;

    @ApplicationModuleListener
    void on(PayoutAccountChanged event) {
        var phase = "effective".equals(event.phase())
                ? BankAccountChange.Phase.EFFECTIVE
                : BankAccountChange.Phase.REQUESTED;
        var payouts = links.studio(event.merchantId(), "payouts");
        notify(
                event.eventId(),
                event.merchantId(),
                OWNERS,
                null,
                business -> new BankAccountChange(business, phase, event.effectiveAt(), payouts));
    }

    @ApplicationModuleListener
    void on(ca.northline.payments.api.PayoutSent event) {
        var payouts = links.studio(event.merchantId(), "payouts");
        notify(
                event.eventId(),
                event.merchantId(),
                FINANCE,
                "payout",
                business -> new PayoutSent(
                        business,
                        event.amountCents(),
                        event.feeCents(),
                        "instant".equals(event.kind()),
                        event.arrivesAt(),
                        payouts));
    }

    @ApplicationModuleListener
    void on(DisputeUpdated event) {
        var change = DisputeUpdate.Change.valueOf(event.change().toUpperCase(Locale.ROOT));
        var cases = links.studio(event.merchantId(), "refunds");
        notify(
                event.eventId(),
                event.merchantId(),
                OWNERS,
                "dispute",
                business -> new DisputeUpdate(
                        business,
                        event.caseNumber(),
                        change,
                        event.amountCents(),
                        event.respondBy(),
                        null,
                        0,
                        cases,
                        null));
    }

    @ApplicationModuleListener
    void on(DisputeDecided event) {
        var cases = links.studio(event.merchantId(), "refunds");
        notify(
                event.eventId(),
                event.merchantId(),
                OWNERS,
                "dispute",
                business -> new DisputeUpdate(
                        business,
                        event.caseNumber(),
                        DisputeUpdate.Change.DECIDED,
                        event.amountCents(),
                        null,
                        event.decision(),
                        event.refundCents(),
                        cases,
                        event.note()));
    }

    @ApplicationModuleListener
    void on(RefundCaseUpdated event) {
        var change = RefundCaseUpdate.Change.valueOf(event.change().toUpperCase(Locale.ROOT));
        var cases = links.studio(event.merchantId(), "refunds");
        notify(
                event.eventId(),
                event.merchantId(),
                OWNERS,
                "dispute",
                business -> new RefundCaseUpdate(
                        business, event.caseNumber(), change, event.amountCents(), event.respondBy(), cases));
    }

    @ApplicationModuleListener
    void on(RefundIssued event) {
        var cases = links.studio(event.merchantId(), "refunds");
        notify(
                event.eventId(),
                event.merchantId(),
                OWNERS,
                "dispute",
                business -> new RefundCaseUpdate(
                        business, event.caseNumber(), RefundCaseUpdate.Change.PAID, event.amountCents(), null, cases));
    }

    /**
     * S-31: the page's own domain went live, stopped pointing at Northline (grace period), was disconnected, failed its
     * certificate, expired or was claimed by another business. A service notice to the owners, always sent.
     */
    @ApplicationModuleListener
    void on(CustomDomainChanged event) {
        var notice = event.notice();
        if (notice == null) {
            return;
        }
        var change = CustomDomainNotice.Change.valueOf(notice.toUpperCase(Locale.ROOT));
        var page = links.studio(event.merchantId(), "page");
        notify(
                event.eventId(),
                event.merchantId(),
                OWNERS,
                null,
                business -> new CustomDomainNotice(business, event.domain(), change, event.graceEndsAt(), page));
    }

    /**
     * S-79: the console's verification queue approved the application or sent it back with checks to redo. The
     * answer to the owners' own submission: always sent, to every owner.
     */
    @ApplicationModuleListener
    void on(ApplicationDecided event) {
        var approved = "approved".equals(event.decision());
        var link = approved ? links.studioHome(event.aggregateId()) : links.onboardingVerification(event.aggregateId());
        notify(
                event.eventId(),
                event.aggregateId(),
                OWNERS,
                null,
                business -> new ApplicationDecision(business, event.decision(), event.checkKeys(), event.note(), link));
    }

    /**
     * S-92: a reviewer rejected a listing or a dish in the console's vetting queue. A service notice about the
     * business's own listing: always sent, to the owners.
     */
    @ApplicationModuleListener
    void on(ListingRejectedNotice event) {
        var link = links.studio(event.merchantId(), "dish".equals(event.kind()) ? "kitchen/menu" : "listings");
        notify(
                event.eventId(),
                event.merchantId(),
                OWNERS,
                null,
                business -> new ListingRejected(
                        business, event.kind(), event.listingName(), event.reasons(), event.note(), link));
    }

    /** S-93: trust &amp; safety warned the business from a flag; always sent, to the owners. */
    @ApplicationModuleListener
    void on(TrustWarningNotice event) {
        var link = links.studioHome(event.merchantId());
        notify(
                event.eventId(),
                event.merchantId(),
                OWNERS,
                null,
                business -> new TrustWarning(business, event.rule(), event.note(), link));
    }

    /**
     * @param row Settings › Notifications row governing this email; null = a security notice that is always sent
     */
    private void notify(
            String eventId,
            String merchantId,
            Set<MerchantRole> roles,
            @Nullable String row,
            Function<String, EmailContent> content) {
        var business = businesses.displayName(merchantId).orElse("Northline");
        var members = roster.members(merchantId).stream()
                .filter(m -> roles.contains(m.role()))
                .map(TeamRoster.TeamMember::userId)
                .distinct()
                .toList();
        var people = contacts.contacts(members);
        EmailDeliveryFailed outage = null;
        for (var userId : members) {
            var person = people.get(userId);
            if (person == null || person.email() == null || person.email().isBlank()) {
                continue;
            }
            if (row != null && !wantsEmail(userId, row)) {
                log.debug("{} turned '{}' emails off; skipping {}", userId, row, eventId);
                continue;
            }
            EmailAddress to;
            try {
                to = new EmailAddress(person.email(), person.displayName());
            } catch (IllegalArgumentException e) {
                log.warn("Member {} of {} has an unusable email address; skipping {}", userId, merchantId, eventId);
                continue;
            }
            var unsubscribe = row == null ? null : links.unsubscribe(tokens.issue(userId, row, person.locale()));
            try {
                mailer.send(new Mailer.Delivery(
                        eventId + ":" + userId, to, content.apply(business), person.locale(), unsubscribe));
            } catch (EmailDeliveryFailed e) {
                outage = e; // try the other members first; the sent ones are remembered
            }
        }
        if (outage != null) {
            throw outage;
        }
    }

    private boolean wantsEmail(String userId, String row) {
        return prefs.find(userId).orElseGet(NotificationMatrix::defaultsOnly).wants(row, "email");
    }
}
