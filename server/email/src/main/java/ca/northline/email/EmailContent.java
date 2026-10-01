package ca.northline.email;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What an email says, independent of language: one record per template in {@code email/templates/<template>.html|txt}
 * (copy in {@code email/messages[_fr].properties}). Values are raw here and formatted for the recipient's locale by
 * {@link #variables(EmailFormat)}. No record carries the recipient's address — the {@link Mailer} gets it separately.
 */
public sealed interface EmailContent {

    /** CASL class of the message; decides the footer and whether an unsubscribe link is required. */
    enum Purpose {
        /**
         * Facilitates or confirms something the recipient (or someone on their behalf) asked for, or is a security or
         * account notice (CASL s. 6(6)): no unsubscribe link, but the sender is always identified.
         */
        TRANSACTIONAL,
        /**
         * An account notification the recipient can turn off in Settings › Notifications: carries a one-click
         * unsubscribe link that turns that notification's email off.
         */
        NOTIFICATION,
        /** A commercial electronic message (CASL): needs consent and an unsubscribe link. None exist yet. */
        COMMERCIAL;

        public boolean needsUnsubscribe() {
            return this != TRANSACTIONAL;
        }
    }

    /** Template file name without extension, e.g. {@code team-invitation}. */
    String template();

    /** Sub-key of the subject for templates with variants ({@code dispute-update.subject.opened}); empty = none. */
    default String variant() {
        return "";
    }

    Purpose purpose();

    String businessName();

    /** Template variables, formatted for the recipient. Keys are the template's names. */
    Map<String, Object> variables(EmailFormat format);

    /** Arguments of {@code <template>.subject[.<variant>]}. */
    default List<Object> subjectArgs(EmailFormat format) {
        return List.of(businessName());
    }

    /** Arguments of {@code <template>.reason} (the footer line saying why this address got the email). */
    default List<Object> reasonArgs() {
        return List.of(businessName());
    }

    /** Message-key form of an enum constant: {@code OFFER_DECLINED} → {@code offer_declined}. */
    private static String code(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    // ── Team ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Settings › Team: an owner invited this address. Transactional (sent because of the owner's request, and the
     * address has no Northline preferences to honour).
     *
     * @param role {@code owner | technician | cook | bookkeeper}
     * @param link {@code <studio>/invite/<token>}
     */
    record TeamInvitation(String businessName, String inviterName, String role, URI link, Instant expiresAt)
            implements EmailContent {

        @Override
        public String template() {
            return "team-invitation";
        }

        @Override
        public Purpose purpose() {
            return Purpose.TRANSACTIONAL;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            return Map.of(
                    "businessName", businessName,
                    "inviterName", inviterName,
                    "role", role,
                    "link", link.toString(),
                    "expiresAt", format.date(expiresAt));
        }

        @Override
        public List<Object> subjectArgs(EmailFormat format) {
            return List.of(inviterName, businessName);
        }

        @Override
        public List<Object> reasonArgs() {
            return List.of(inviterName, businessName);
        }
    }

    // ── Onboarding ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Onboarding › Verification (S-22): an owner of the business asked Northline to send this owner their Stripe
     * Identity link (government ID + selfie). Transactional (requested on the recipient's behalf by their business).
     *
     * @param ownerName the principal's legal name as entered in the application
     * @param link Stripe's hosted verification flow (single use; Stripe expires it)
     */
    record IdentityVerificationLink(String businessName, String requesterName, String ownerName, URI link)
            implements EmailContent {

        @Override
        public String template() {
            return "identity-verification";
        }

        @Override
        public Purpose purpose() {
            return Purpose.TRANSACTIONAL;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            return Map.of(
                    "businessName", businessName,
                    "requesterName", requesterName,
                    "ownerName", ownerName,
                    "link", link.toString());
        }

        @Override
        public List<Object> reasonArgs() {
            return List.of(requesterName, businessName);
        }
    }

    // ── Finance ──────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Payouts › bank account: the account payouts go to is changing ({@code payout_account.changed}). A security
     * notice — always sent, never subject to preferences.
     *
     * @param effectiveAt when the new account takes over (the end of the 24 h hold)
     */
    record BankAccountChange(String businessName, Phase phase, Instant effectiveAt, URI payoutsLink)
            implements EmailContent {

        public enum Phase {
            /** Confirmed by the owner; payouts pause until {@code effectiveAt}. */
            REQUESTED,
            /** The hold ended; payouts go to the new account. */
            EFFECTIVE
        }

        @Override
        public String template() {
            return "bank-account-change";
        }

        @Override
        public String variant() {
            return code(phase);
        }

        @Override
        public Purpose purpose() {
            return Purpose.TRANSACTIONAL;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            return Map.of(
                    "businessName", businessName,
                    "phase", code(phase),
                    "effectiveAt", format.dateTime(effectiveAt),
                    "link", payoutsLink.toString());
        }
    }

    /**
     * Payouts: money left for the bank ({@code payout.sent}) — the receipt. Settings › Notifications row
     * {@code payout}.
     */
    record PayoutSent(
            String businessName, long amountCents, long feeCents, boolean instant, Instant arrivesAt, URI payoutsLink)
            implements EmailContent {

        @Override
        public String template() {
            return "payout-sent";
        }

        @Override
        public Purpose purpose() {
            return Purpose.NOTIFICATION;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("amount", format.money(amountCents));
            v.put("fee", format.money(feeCents));
            v.put("hasFee", feeCents > 0);
            v.put("net", format.money(amountCents - feeCents));
            v.put("kind", instant ? "instant" : "scheduled");
            v.put("arrivesAt", instant ? format.dateTime(arrivesAt) : format.date(arrivesAt));
            v.put("link", payoutsLink.toString());
            return v;
        }

        @Override
        public List<Object> subjectArgs(EmailFormat format) {
            return List.of(format.money(amountCents - feeCents), businessName);
        }
    }

    /**
     * Payouts: the bank returned a payout, or it was canceled ({@code payout.failed}, S-27 — sent by the worker's
     * notifications consumer). The money is back in the Northline balance; the owner checks the bank details.
     * Settings › Notifications row {@code payout}.
     *
     * @param outcome {@code failed} (returned by the bank) | {@code canceled}
     * @param failureCode Stripe's code ({@code account_closed}, {@code no_account}, …), null when canceled
     */
    record PayoutFailed(
            String businessName,
            long amountCents,
            String outcome,
            @Nullable String failureCode,
            URI payoutsLink) implements EmailContent {

        public PayoutFailed {
            if (!outcome.equals("failed") && !outcome.equals("canceled")) {
                throw new IllegalArgumentException("outcome must be failed or canceled: " + outcome);
            }
        }

        @Override
        public String template() {
            return "payout-failed";
        }

        @Override
        public String variant() {
            return outcome;
        }

        @Override
        public Purpose purpose() {
            return Purpose.NOTIFICATION;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("outcome", outcome);
            v.put("amount", format.money(amountCents));
            v.put("hasCode", failureCode != null && !failureCode.isBlank());
            v.put("code", failureCode == null ? "" : failureCode);
            v.put("link", payoutsLink.toString());
            return v;
        }

        @Override
        public List<Object> subjectArgs(EmailFormat format) {
            return List.of(format.money(amountCents), businessName);
        }
    }

    /**
     * Refunds &amp; disputes: a customer's dispute opened, changed hands or was decided. Settings › Notifications row
     * {@code dispute}.
     *
     * @param respondBy the merchant's deadline (opened) — null otherwise
     * @param decision {@code release | goodwill | partial | full_refund} when {@code change = DECIDED}
     * @param refundCents what the customer gets back when decided
     */
    record DisputeUpdate(
            String businessName,
            String caseNumber,
            DisputeUpdate.Change change,
            long amountCents,
            @Nullable Instant respondBy,
            @Nullable String decision,
            long refundCents,
            URI casesLink,
            @Nullable String note)
            implements EmailContent {

        public enum Change {
            OPENED,
            OFFER_DECLINED,
            OFFER_EXPIRED,
            DECIDED
        }

        @Override
        public String template() {
            return "dispute-update";
        }

        @Override
        public String variant() {
            return code(change);
        }

        @Override
        public Purpose purpose() {
            return Purpose.NOTIFICATION;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("caseNumber", caseNumber);
            v.put("change", code(change));
            v.put("amount", format.money(amountCents));
            v.put("respondBy", respondBy == null ? "" : format.dateTime(respondBy));
            v.put("decision", decision == null ? "" : decision);
            v.put("refund", format.money(refundCents));
            v.put("link", casesLink.toString());
            v.put("note", note == null ? "" : note);
            return v;
        }

        @Override
        public List<Object> subjectArgs(EmailFormat format) {
            return List.of(caseNumber, businessName);
        }
    }

    /**
     * Refunds &amp; disputes: a customer's refund request and what happened to it. Settings › Notifications row
     * {@code dispute} (the design has no separate refunds row).
     *
     * @param respondBy the merchant's deadline while the case waits for them (requested) — null otherwise
     */
    record RefundCaseUpdate(
            String businessName,
            String caseNumber,
            RefundCaseUpdate.Change change,
            long amountCents,
            @Nullable Instant respondBy,
            URI casesLink)
            implements EmailContent {

        public enum Change {
            /** The customer asked; the merchant reviews it (small amounts are approved unless contested). */
            REQUESTED,
            /** Approved (by an agent or because the review window lapsed); the refund queue will pay it. */
            APPROVED,
            /** The review window lapsed on a larger refund; a Northline agent decides. */
            AGENT_REVIEW,
            /** A Northline agent denied it; the held money is released. */
            DENIED,
            /** Paid back to the customer's original payment method. */
            PAID
        }

        @Override
        public String template() {
            return "refund-case-update";
        }

        @Override
        public String variant() {
            return code(change);
        }

        @Override
        public Purpose purpose() {
            return Purpose.NOTIFICATION;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("caseNumber", caseNumber);
            v.put("change", code(change));
            v.put("amount", format.money(amountCents));
            v.put("respondBy", respondBy == null ? "" : format.dateTime(respondBy));
            v.put("link", casesLink.toString());
            return v;
        }

        @Override
        public List<Object> subjectArgs(EmailFormat format) {
            return List.of(caseNumber, format.money(amountCents), businessName);
        }
    }

    // ── Developer ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Settings › API &amp; integrations: the webhook worker turned an endpoint off after sustained failure (S-33).
     * Transactional (an account notice about the owner's own integration; owners only, whatever the matrix).
     *
     * @param lastError the latest attempt's outcome ("HTTP 503", "timed out"), null when unknown
     */
    record WebhookDisabled(
            String businessName,
            String url,
            Instant failingSince,
            @Nullable String lastError,
            URI settingsLink) implements EmailContent {

        @Override
        public String template() {
            return "webhook-disabled";
        }

        @Override
        public Purpose purpose() {
            return Purpose.TRANSACTIONAL;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("url", url);
            v.put("failingSince", format.date(failingSince));
            v.put("hasError", lastError != null && !lastError.isBlank());
            v.put("lastError", lastError == null ? "" : lastError);
            v.put("link", settingsLink.toString());
            return v;
        }
    }

    // ── Storefront ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Studio › Business page (S-31): something happened to the page's own domain that the owners must know — it went
     * live, its DNS records stopped pointing at Northline (it keeps serving until {@code deadline}), it was disconnected
     * after that, its certificate could not be issued, verification expired, or another business claimed it.
     * Transactional: a service notice about the owners' own configuration, always sent.
     *
     * @param deadline end of the grace period ({@code dns_lost}); null otherwise
     * @param pageLink Studio › Business page / Store / Menu page
     */
    record CustomDomainNotice(
            String businessName,
            String domain,
            CustomDomainNotice.Change change,
            @Nullable Instant deadline,
            URI pageLink)
            implements EmailContent {

        public enum Change {
            LIVE,
            DNS_LOST,
            UNVERIFIED,
            CERTIFICATE_FAILED,
            EXPIRED,
            RELEASED
        }

        @Override
        public String template() {
            return "custom-domain";
        }

        @Override
        public String variant() {
            return code(change);
        }

        @Override
        public Purpose purpose() {
            return Purpose.TRANSACTIONAL;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("domain", domain);
            v.put("change", code(change));
            v.put("deadline", deadline == null ? "" : format.dateTime(deadline));
            v.put("link", pageLink.toString());
            return v;
        }

        @Override
        public List<Object> subjectArgs(EmailFormat format) {
            return List.of(domain, businessName);
        }
    }

    // ── Verification (S-79) ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * The console's verification queue decided the application: approved (the business starts at Registered), or sent
     * back with the checks to redo and the agent's note. Transactional: the answer to the owners' own submission.
     *
     * @param decision {@code approved | info_requested}
     * @param checkKeys checklist keys to redo ({@code insurance}, {@code licence:AMVIC}); empty when approved
     * @param note the agent's words to the business, or null
     * @param link the Studio (approved) or the onboarding Verification step (info requested)
     */
    record ApplicationDecision(
            String businessName,
            String decision,
            List<String> checkKeys,
            @Nullable String note,
            URI link) implements EmailContent {

        /** Checklist keys the template names; anything else is shown as its key. */
        private static final Set<String> KNOWN_CHECKS = Set.of(
                "kyc",
                "registry",
                "gst",
                "ahs_permit",
                "food_cert",
                "inspection",
                "insurance",
                "category_permits",
                "product_safety",
                "returns_policy",
                "allergen_attestation",
                "aglc",
                "bank",
                "mfa",
                "site_visit");

        public ApplicationDecision {
            checkKeys = List.copyOf(checkKeys);
        }

        @Override
        public String template() {
            return "application-decision";
        }

        @Override
        public String variant() {
            return decision;
        }

        @Override
        public Purpose purpose() {
            return Purpose.TRANSACTIONAL;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var checks = checkKeys.stream()
                    .map(key -> key.startsWith("licence:")
                            ? Map.of("code", "licence", "arg", key.substring("licence:".length()))
                            : KNOWN_CHECKS.contains(key)
                                    ? Map.of("code", key, "arg", "")
                                    : Map.of("code", "other", "arg", key))
                    .toList();
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("decision", decision);
            v.put("checks", checks);
            v.put("note", note == null ? "" : note);
            v.put("link", link.toString());
            return v;
        }
    }

    /**
     * S-92: a Northline reviewer rejected a listing or a dish (console listing vetting). Transactional: a service notice
     * about the business's own listing, sent to the owners whatever the matrix.
     *
     * @param kind {@code product | service | dish}
     * @param reasons {@code prohibited | misleading | pricing | licence | images | other}
     */
    record ListingRejected(
            String businessName,
            String kind,
            String listingName,
            List<String> reasons,
            @Nullable String note,
            URI link) implements EmailContent {

        public ListingRejected {
            reasons = List.copyOf(reasons);
        }

        @Override
        public String template() {
            return "listing-rejected";
        }

        @Override
        public Purpose purpose() {
            return Purpose.TRANSACTIONAL;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("kind", kind);
            v.put("listingName", listingName);
            v.put("reasons", reasons);
            v.put("note", note == null ? "" : note);
            v.put("link", link.toString());
            return v;
        }

        @Override
        public List<Object> subjectArgs(EmailFormat format) {
            return List.of(listingName, businessName);
        }
    }

    /**
     * S-93: trust &amp; safety warned the business from a flag (off-platform payment attempt, a floor breach …).
     * Transactional: an account notice, always sent to the owners.
     *
     * @param rule the flag's rule ({@code off_platform_payment}, …); unknown rules use the generic wording
     */
    record TrustWarning(
            String businessName, String rule, @Nullable String note, URI link) implements EmailContent {

        @Override
        public String template() {
            return "trust-warning";
        }

        @Override
        public Purpose purpose() {
            return Purpose.TRANSACTIONAL;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("rule", "off_platform_payment".equals(rule) ? rule : "other");
            v.put("note", note == null ? "" : note);
            v.put("link", link.toString());
            return v;
        }
    }

    // ── Console oversight ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * S-82: Northline staff acted on the business from the console — suspended or reinstated it, asked for one of its
     * checks again, or changed its tier — with the reason they gave. Transactional: an account notice to the owners,
     * always sent.
     *
     * @param fromTier / {@code toTier}: the tiers ({@code tier_changed}); {@code checkType} the check asked for again
     *     ({@code reverification_required}); null otherwise
     * @param link Studio › Help (suspension, reinstatement, tier) or Compliance (re-verification)
     */
    record SellerOversightNotice(
            String businessName,
            SellerOversightNotice.Action action,
            String reason,
            @Nullable String fromTier,
            @Nullable String toTier,
            @Nullable String checkType,
            URI link)
            implements EmailContent {

        public enum Action {
            SUSPENDED,
            REINSTATED,
            REVERIFICATION_REQUIRED,
            TIER_CHANGED
        }

        @Override
        public String template() {
            return "seller-oversight";
        }

        @Override
        public String variant() {
            return code(action);
        }

        @Override
        public Purpose purpose() {
            return Purpose.TRANSACTIONAL;
        }

        @Override
        public Map<String, Object> variables(EmailFormat format) {
            var v = new LinkedHashMap<String, Object>();
            v.put("businessName", businessName);
            v.put("action", code(action));
            v.put("staffReason", reason);
            v.put("fromTier", fromTier == null ? "" : fromTier);
            v.put("toTier", toTier == null ? "" : toTier);
            v.put("checkType", checkType == null ? "" : checkType);
            v.put("link", link.toString());
            return v;
        }
    }

    // ── Samples (preview endpoint, rendering tests) ──────────────────────────────────────────────────────────────

    /**
     * One sample per template and variant, keyed {@code <template>[.<variant>]}, with the design's Prairie Wrench
     * data. Used by the local preview endpoint and the rendering tests.
     */
    static Map<String, EmailContent> samples() {
        var at = Instant.parse("2026-10-02T15:00:00Z");
        var studio = "http://localhost:3100/b/01J9ZD3V00000000000000PWM1";
        var business = "Prairie Wrench";
        var payouts = URI.create(studio + "/payouts");
        var cases = URI.create(studio + "/refunds");
        var all = new LinkedHashMap<String, EmailContent>();
        all.put(
                "team-invitation",
                new TeamInvitation(
                        business,
                        "Ravi Sandhu",
                        "technician",
                        URI.create("http://localhost:3100/invite/sample-token"),
                        at.plus(Duration.ofDays(7))));
        all.put(
                "identity-verification",
                new IdentityVerificationLink(
                        business,
                        "Ravi Sandhu",
                        "Priya Sandhu",
                        URI.create("https://verify.stripe.com/start/test_sample")));
        for (var phase : BankAccountChange.Phase.values()) {
            var sample = new BankAccountChange(business, phase, at.plus(Duration.ofHours(24)), payouts);
            all.put(key(sample), sample);
        }
        all.put("payout-sent", new PayoutSent(business, 82_260, 823, true, at.plus(Duration.ofMinutes(30)), payouts));
        all.put(
                "payout-sent.scheduled",
                new PayoutSent(business, 145_000, 0, false, at.plus(Duration.ofDays(1)), payouts));
        all.put("payout-failed.failed", new PayoutFailed(business, 81_437, "failed", "account_closed", payouts));
        all.put("payout-failed.canceled", new PayoutFailed(business, 81_437, "canceled", null, payouts));
        for (var change : DisputeUpdate.Change.values()) {
            var decided = change == DisputeUpdate.Change.DECIDED;
            var sample = new DisputeUpdate(
                    business,
                    "DS-1188",
                    change,
                    38_900,
                    change == DisputeUpdate.Change.OPENED ? at.plus(Duration.ofDays(3)) : null,
                    decided ? "partial" : null,
                    decided ? 19_450 : 0,
                    cases,
                    decided ? "Photos show the leak after the visit; half the fee is refunded." : null);
            all.put(key(sample), sample);
        }
        for (var decision : List.of("release", "goodwill", "full_refund")) {
            all.put(
                    "dispute-update.decided." + decision,
                    new DisputeUpdate(
                            business,
                            "DS-1188",
                            DisputeUpdate.Change.DECIDED,
                            38_900,
                            null,
                            decision,
                            switch (decision) {
                                case "release" -> 0;
                                case "goodwill" -> 19_450;
                                default -> 38_900;
                            },
                            cases,
                            null));
        }
        for (var change : RefundCaseUpdate.Change.values()) {
            var sample = new RefundCaseUpdate(
                    business,
                    "RF-2214",
                    change,
                    4_500,
                    change == RefundCaseUpdate.Change.REQUESTED ? at.plus(Duration.ofHours(24)) : null,
                    cases);
            all.put(key(sample), sample);
        }
        all.put(
                "webhook-disabled",
                new WebhookDisabled(
                        business,
                        "https://prairiewrench.ca/hooks/northline",
                        at.minus(Duration.ofDays(3)),
                        "HTTP 503",
                        URI.create(studio + "/settings?tab=api")));
        for (var change : CustomDomainNotice.Change.values()) {
            all.put(
                    "custom-domain." + code(change),
                    new CustomDomainNotice(
                            business,
                            "book.prairiewrench.ca",
                            change,
                            change == CustomDomainNotice.Change.DNS_LOST ? at.plus(Duration.ofDays(3)) : null,
                            URI.create(studio + "/page")));
        }
        all.put(
                "application-decision.approved",
                new ApplicationDecision(business, "approved", List.of(), null, URI.create(studio)));
        all.put(
                "application-decision.info_requested",
                new ApplicationDecision(
                        business,
                        "info_requested",
                        List.of("insurance", "licence:AMVIC"),
                        "Your insurance certificate is cut off at the bottom; please upload all pages.",
                        URI.create("http://localhost:3100/onboarding/verification?m=01J9ZD3V00000000000000PWM1")));
        all.put(
                "listing-rejected",
                new ListingRejected(
                        business,
                        "service",
                        "Full brake job",
                        List.of("pricing", "misleading"),
                        "The price is far below what the job costs; customers would be charged more on site.",
                        URI.create(studio + "/listings")));
        all.put(
                "trust-warning",
                new TrustWarning(
                        business,
                        "off_platform_payment",
                        "A message asked a customer to e-transfer you directly.",
                        URI.create(studio + "/messages")));
        for (var action : SellerOversightNotice.Action.values()) {
            all.put(
                    "seller-oversight." + code(action),
                    new SellerOversightNotice(
                            business,
                            action,
                            "Quality 78 is below the Trusted floor (80) for the third week.",
                            action == SellerOversightNotice.Action.TIER_CHANGED ? "trusted" : null,
                            action == SellerOversightNotice.Action.TIER_CHANGED ? "registered" : null,
                            action == SellerOversightNotice.Action.REVERIFICATION_REQUIRED ? "insurance" : null,
                            URI.create(studio + "/help")));
        }
        return java.util.Collections.unmodifiableMap(all);
    }

    private static String key(EmailContent content) {
        return content.variant().isEmpty() ? content.template() : content.template() + "." + content.variant();
    }
}
