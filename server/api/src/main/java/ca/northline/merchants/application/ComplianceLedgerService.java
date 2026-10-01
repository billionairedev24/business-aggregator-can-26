package ca.northline.merchants.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.ComplianceDocuments;
import ca.northline.merchants.api.ComplianceStatus;
import ca.northline.merchants.api.VerificationRenewalSubmitted;
import ca.northline.merchants.application.ComplianceUseCases.AcceptObligations;
import ca.northline.merchants.application.ComplianceUseCases.Business;
import ca.northline.merchants.application.ComplianceUseCases.ComplianceView;
import ca.northline.merchants.application.ComplianceUseCases.ObligationsView;
import ca.northline.merchants.application.ComplianceUseCases.OpenStripeLink;
import ca.northline.merchants.application.ComplianceUseCases.RenewVerification;
import ca.northline.merchants.application.ComplianceUseCases.StripeView;
import ca.northline.merchants.application.ComplianceUseCases.TaxRow;
import ca.northline.merchants.application.ComplianceUseCases.ViewCompliance;
import ca.northline.merchants.application.Documents.UploadDocument;
import ca.northline.merchants.domain.ComplianceItem;
import ca.northline.merchants.domain.ComplianceRules;
import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.VerificationStatus;
import ca.northline.payments.api.ConnectedAccounts;
import ca.northline.payments.api.PayoutPlan;
import ca.northline.payments.api.TaxSummary;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stripe &amp; compliance: the Connect account (through {@link ConnectAccountGateway}), payment settings, tax by
 * jurisdiction ({@link TaxSummary}), the licence / insurance / policy ledger with renewals, and the accepted platform
 * obligations. Also answers {@link ComplianceStatus} for the dashboard. Onboarding creates the verification rows; this
 * service owns what happens to them after approval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ComplianceLedgerService
        implements ViewCompliance,
                RenewVerification,
                AcceptObligations,
                OpenStripeLink,
                ComplianceStatus,
                ComplianceDocuments {

    private final ComplianceLedgerStore ledger;
    private final ConnectAccountGateway stripe;
    private final TaxSummary tax;
    private final ConnectedAccounts connectedAccounts;
    private final PayoutPlan payoutPlan;
    private final UploadDocument uploads;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final StudioLinks links;
    private final Clock clock;
    private final MerchantPlaces places;

    @Override
    public ComplianceView view(String merchantId) {
        var facts = facts(merchantId);
        var now = clock.instant();
        var items = ledger.items(merchantId, now);
        var period = quarter(merchantId);
        return new ComplianceView(
                new Business(
                        facts.type().code(),
                        facts.displayName(),
                        facts.legalName(),
                        facts.businessNumber(),
                        facts.province(),
                        facts.requiredFor(),
                        facts.ownerName(),
                        facts.takeRateBps()),
                stripeView(merchantId, facts.stripeAccountId(), facts.displayName()),
                period,
                tax.totals(merchantId, period).stream()
                        .map(t -> new TaxRow(t.jurisdiction(), t.collectedCents(), t.handling()))
                        .toList(),
                items,
                obligations(merchantId),
                (int) items.stream().filter(ComplianceItem::due).count());
    }

    @Override
    @Transactional
    public ComplianceItem renew(RenewVerification.Command command) {
        var actor = command.actor();
        var now = clock.instant();
        var item = ledger.item(actor.merchantId(), command.verificationId(), now)
                .orElseThrow(() -> new NotFound("verification", command.verificationId()));
        if (!item.renewable()) {
            throw new Conflict("renewal_pending", "A document for this check is already waiting for review.");
        }
        if (command.bytes().isEmpty()) {
            throw RuleViolation.of(Documents.FIELD, "required", ComplianceRules.DOCUMENT_REQUIRED);
        }
        var document = uploads.upload(new UploadDocument.Command(
                actor.merchantId(),
                actor.userId(),
                Document.Purpose.VERIFICATION,
                command.fileName(),
                command.contentType(),
                command.bytes()));
        ledger.submitRenewal(item.id(), document.id(), actor.userId(), now);
        audit.record(AuditTrail.Entry.of(
                        actor.merchantId(),
                        actor.userId(),
                        actor.role().code(),
                        "compliance.document_uploaded",
                        "verification",
                        item.id())
                .withChange(Map.of("status", item.status().code()), Map.of("status", "submitted")));
        events.publishEvent(new VerificationRenewalSubmitted(
                Ids.next(),
                now,
                item.id(),
                actor.userId(),
                actor.merchantId(),
                item.checkType().code(),
                document.id()));
        return ledger.item(actor.merchantId(), item.id(), now).orElseThrow();
    }

    @Override
    @Transactional
    public ObligationsView accept(SettingsActor actor, String version) {
        if (!ComplianceRules.OBLIGATIONS_VERSION.equals(version)) {
            throw new Conflict("obligations_outdated", "Reload the page to read the current version first.");
        }
        var latest = ledger.latestAcceptance(actor.merchantId());
        if (latest.isEmpty() || !latest.get().version().equals(version)) {
            ledger.accept(actor.merchantId(), version, actor.userId(), clock.instant());
            audit.record(AuditTrail.Entry.of(
                            actor.merchantId(),
                            actor.userId(),
                            actor.role().code(),
                            "compliance.obligations_accepted",
                            "merchant",
                            actor.merchantId())
                    .withChange(null, Map.of("version", version)));
        }
        return obligations(actor.merchantId());
    }

    @Override
    @Transactional
    public String link(SettingsActor actor, String kind) {
        var facts = facts(actor.merchantId());
        var account = facts.stripeAccountId();
        if ("dashboard".equals(kind)) {
            if (account == null) {
                throw new Conflict("stripe_not_connected", "Set up payouts with Stripe first.");
            }
            return stripe.dashboardLink(account);
        }
        if (!"update".equals(kind)) {
            throw new NotFound("stripe link", kind);
        }
        if (account == null) {
            account = stripe.createExpressAccount(actor.merchantId());
            ledger.linkStripeAccount(actor.merchantId(), account);
        }
        connectedAccounts.linked(actor.merchantId(), account);
        var back = links.compliance(actor.merchantId());
        return stripe.onboardingLink(account, back, back + "?stripe=refresh");
    }

    @Override
    public List<LedgerDocument> documents(String merchantId) {
        return ledger.items(merchantId, clock.instant()).stream()
                .sorted(Comparator.comparing(
                        ComplianceItem::expiresAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(i -> new LedgerDocument(
                        i.id(),
                        i.checkType().code(),
                        i.registry(),
                        i.reference(),
                        i.label(),
                        i.status().code(),
                        i.expiresAt()))
                .toList();
    }

    @Override
    public List<DueItem> dueItems(String merchantId) {
        var now = clock.instant();
        return Stream.concat(ledger.items(merchantId, now).stream(), ledger.platformChecks(merchantId, now).stream())
                .filter(ComplianceItem::due)
                .sorted(Comparator.comparing(
                        ComplianceItem::expiresAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(i -> new DueItem(
                        i.checkType().code(),
                        i.registry(),
                        i.status() == VerificationStatus.EXPIRED ? "expired" : "todo",
                        i.expiresAt(),
                        i.pausesAt()))
                .toList();
    }

    /**
     * The payout schedule and instant eligibility shown are Northline's (payments runs the payouts; Stripe's own
     * schedule is always manual), falling back to Stripe's when payments has no record of the account yet.
     */
    private StripeView stripeView(String merchantId, @Nullable String accountId, String displayName) {
        if (accountId == null) {
            return new StripeView("not_connected", null, null, false, false, List.of(), null, null, null, null, false);
        }
        try {
            var plan = payoutPlan.of(merchantId);
            return stripe.account(accountId)
                    .map(a -> new StripeView(
                            "connected",
                            mask(a.id()),
                            a.type(),
                            a.chargesEnabled(),
                            a.payoutsEnabled(),
                            a.requirements(),
                            a.bankLabel(),
                            Objects.requireNonNullElse(a.statementDescriptor(), descriptor(displayName)),
                            plan.map(PayoutPlan.Plan::interval).orElse(a.payoutInterval()),
                            plan.isPresent() ? plan.get().weekday() : a.payoutWeekday(),
                            plan.map(PayoutPlan.Plan::instantPayouts).orElse(a.instantPayouts())))
                    .orElseGet(() -> unavailable(accountId));
        } catch (RuntimeException e) {
            log.warn("Stripe account {} could not be read: {}", accountId, e.getMessage());
            return unavailable(accountId);
        }
    }

    private static StripeView unavailable(String accountId) {
        return new StripeView(
                "unavailable", mask(accountId), null, false, false, List.of(), null, null, null, null, false);
    }

    private ObligationsView obligations(String merchantId) {
        var latest = ledger.latestAcceptance(merchantId);
        return new ObligationsView(
                ComplianceRules.OBLIGATIONS_VERSION,
                latest.map(ComplianceLedgerStore.Acceptance::version).orElse(null),
                latest.map(ComplianceLedgerStore.Acceptance::acceptedAt).orElse(null),
                latest.map(a -> Objects.equals(a.version(), ComplianceRules.OBLIGATIONS_VERSION))
                        .orElse(false));
    }

    private ComplianceLedgerStore.BusinessFacts facts(String merchantId) {
        return ledger.facts(merchantId).orElseThrow(() -> new NotFound("merchant", merchantId));
    }

    /** The current quarter in the business's zone (region model). */
    private String quarter(String merchantId) {
        var today = clock.instant().atZone(places.of(merchantId).zone()).toLocalDate();
        return today.getYear() + "-Q" + ((today.getMonthValue() - 1) / 3 + 1);
    }

    /** Stripe's default for a platform-branded descriptor: "NORTHLINE* PRAIRIE WRENCH". */
    static String descriptor(String displayName) {
        return "NORTHLINE* " + displayName.toUpperCase(java.util.Locale.ROOT);
    }

    /** "acct_1Kx9PWM0000000Q2" → "acct_1Kx9…Q2" (design). */
    static String mask(String accountId) {
        return accountId.length() <= 11
                ? accountId
                : accountId.substring(0, 9) + "…" + accountId.substring(accountId.length() - 2);
    }
}
