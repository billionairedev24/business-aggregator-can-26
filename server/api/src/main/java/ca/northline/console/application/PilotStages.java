package ca.northline.console.application;

import ca.northline.console.application.PilotOnboarding.PilotStep;
import ca.northline.merchants.api.PilotCohort.Pilot;
import ca.northline.payments.api.ConnectReadiness;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * S-120: a pilot business's checklist, derived from what the owning modules say. Pure: every input is passed in, so
 * the rules are unit-tested without a database ({@code PilotStagesTest}). The words for each action are the
 * console's (en, fr-CA).
 */
final class PilotStages {

    private PilotStages() {}

    static final String DONE = "done";
    static final String TODO = "todo";
    static final String WAITING = "waiting";
    static final String BLOCKED = "blocked";
    static final String NA = "na";

    /**
     * @param stripe the connected account as Stripe last reported it, null when there is none
     * @param catalogue the business's listings (dishes for a kitchen)
     * @param marketLive the pilot's market takes customers
     */
    record Inputs(Pilot pilot, ConnectReadiness.@Nullable Account stripe, Listings catalogue, boolean marketLive) {}

    /**
     * @param total drafts included
     * @param submitted handed to vetting, or published
     * @param live what customers see
     */
    record Listings(int total, int submitted, int live, boolean setUp) {
        static final Listings NONE = new Listings(0, 0, 0, false);

        /** Services and products: nothing to set up beyond the listings. */
        static Listings of(int total, int submitted, int live) {
            return new Listings(total, submitted, live, true);
        }
    }

    static List<PilotStep> checklist(Inputs in) {
        var p = in.pilot();
        var b = p.business();
        var steps = new ArrayList<PilotStep>();
        var invite = p.invite();
        if (p.merchantId() != null || invite == null) {
            steps.add(step("invited", DONE, null, null, Map.of()));
        } else if ("pending".equals(invite.state())) {
            steps.add(step("invited", DONE, null, null, Map.of()));
        } else {
            steps.add(step("invited", BLOCKED, "resend_invite", "northline", Map.of("state", invite.state())));
        }
        if (b == null) {
            var expires = invite == null
                    ? Map.<String, String>of()
                    : Map.of("expiresAt", invite.expiresAt().toString());
            steps.add(step(
                    "account_created",
                    TODO,
                    invite != null && "pending".equals(invite.state()) ? "accept_invite" : "resend_invite",
                    invite != null && "pending".equals(invite.state()) ? "business" : "northline",
                    expires));
            for (var key : PilotOnboarding.STEPS.subList(2, PilotOnboarding.STEPS.size())) {
                steps.add(
                        key.equals("kitchen_visit") && !"kitchen".equals(p.businessType())
                                ? step(key, NA, null, null, Map.of())
                                : step(key, TODO, null, null, Map.of()));
            }
            return List.copyOf(steps);
        }
        steps.add(step("account_created", DONE, null, null, Map.of()));
        steps.add(
                b.detailsComplete()
                        ? step("details_complete", DONE, null, null, Map.of())
                        : step("details_complete", TODO, "complete_details", "business", Map.of()));
        steps.add(identity(b.identity(), b.identityInReview()));
        steps.add(stripe(in.stripe()));
        steps.add(kitchenVisit(p));
        var c = in.catalogue();
        steps.add(
                c.submitted() > 0
                        ? step("catalogue_ready", DONE, null, null, Map.of("submitted", String.valueOf(c.submitted())))
                        : step(
                                "catalogue_ready",
                                TODO,
                                c.total() > 0 ? "submit_listings" : "add_listings",
                                "business",
                                Map.of("total", String.valueOf(c.total()))));
        steps.add(approval(b));
        steps.add(live(b, c, in.marketLive()));
        return List.copyOf(steps);
    }

    private static PilotStep identity(@Nullable String kyc, boolean inReview) {
        if ("verified".equals(kyc)) {
            return step("identity_verified", DONE, null, null, Map.of());
        }
        if (inReview) {
            return step("identity_verified", WAITING, "decide_identity", "northline", Map.of());
        }
        if ("submitted".equals(kyc)) {
            return step("identity_verified", WAITING, "identity_processing", "stripe", Map.of());
        }
        if ("rejected".equals(kyc)) {
            return step("identity_verified", BLOCKED, "verify_identity_again", "business", Map.of());
        }
        return step("identity_verified", TODO, "verify_identity", "business", Map.of());
    }

    private static PilotStep stripe(ConnectReadiness.@Nullable Account account) {
        if (account == null) {
            return step("stripe_ready", TODO, "connect_stripe", "business", Map.of());
        }
        if (account.ready()) {
            return step("stripe_ready", DONE, null, null, Map.of());
        }
        if (account.unreported()) {
            return step("stripe_ready", WAITING, "stripe_reporting", "stripe", Map.of());
        }
        var params = new LinkedHashMap<String, String>();
        params.put("due", String.valueOf(account.requirementsDue() + account.requirementsPastDue()));
        if (account.disabledReason() != null) {
            params.put("reason", account.disabledReason());
        }
        return step(
                "stripe_ready",
                account.requirementsPastDue() > 0 ? BLOCKED : TODO,
                "stripe_requirements",
                "business",
                params);
    }

    private static PilotStep kitchenVisit(Pilot p) {
        var b = p.business();
        if (b == null || !"kitchen".equals(b.type())) {
            return step("kitchen_visit", NA, null, null, Map.of());
        }
        if ("verified".equals(b.siteVisit())) {
            return step("kitchen_visit", DONE, null, null, Map.of());
        }
        if (!b.kitchenVisitRequired()) {
            // the booked slot is confirmed with the approval (no visit rule here)
            return step("kitchen_visit", NA, null, null, Map.of("rule", "optional"));
        }
        var visit = b.visit();
        if (visit != null && "scheduled".equals(visit.status())) {
            return step(
                    "kitchen_visit",
                    WAITING,
                    "visit_on",
                    "inspector",
                    Map.of("at", visit.scheduledAt().toString()));
        }
        if (visit != null && "failed".equals(visit.status())) {
            return step("kitchen_visit", BLOCKED, "visit_failed", "business", Map.of());
        }
        var slot = b.siteVisitSlot();
        return step(
                "kitchen_visit",
                TODO,
                "schedule_visit",
                "northline",
                slot == null ? Map.of() : Map.of("slot", slot.toString()));
    }

    private static PilotStep approval(ca.northline.merchants.api.PilotCohort.Business b) {
        return switch (b.status()) {
            case "active", "paused" -> step("approved", DONE, null, null, Map.of());
            case "pending" ->
                b.identityInReview() || b.registryReviewsOpen() > 0
                        ? step("approved", WAITING, "decide_reviews", "northline", Map.of())
                        : step("approved", WAITING, "approve", "northline", Map.of());
            case "suspended" -> step("approved", BLOCKED, "suspended", "northline", Map.of());
            default ->
                !b.rejectedChecks().isEmpty()
                        ? step(
                                "approved",
                                BLOCKED,
                                "fix_checks",
                                "business",
                                Map.of("checks", String.join(",", b.rejectedChecks())))
                        : step(
                                "approved",
                                TODO,
                                "submit_application",
                                "business",
                                Map.of(
                                        "complete", String.valueOf(b.checksComplete()),
                                        "total", String.valueOf(b.checksTotal())));
        };
    }

    private static PilotStep live(ca.northline.merchants.api.PilotCohort.Business b, Listings c, boolean marketLive) {
        if (!"active".equals(b.status())) {
            return step("live", TODO, null, null, Map.of());
        }
        if (b.city() == null) {
            return step("live", BLOCKED, "no_market", "northline", Map.of());
        }
        if (!b.storefrontPublished()) {
            return step("live", TODO, "publish_page", "business", Map.of());
        }
        if (!c.setUp()) {
            // a kitchen without its fulfilment and prep settings isn't in the market's kitchen list (dry-run finding)
            return step("live", TODO, "set_up_kitchen", "business", Map.of());
        }
        if (c.live() == 0) {
            return c.submitted() > 0
                    ? step("live", WAITING, "vetting", "northline", Map.of())
                    : step("live", TODO, "add_listings", "business", Map.of());
        }
        if ("pilot".equals(b.searchHidden()) || !marketLive) {
            return step("live", WAITING, "market_launch", "northline", Map.of());
        }
        if (b.searchHidden() != null) {
            return step("live", BLOCKED, "search_hidden", "northline", Map.of("cause", b.searchHidden()));
        }
        return step("live", DONE, null, null, Map.of("listings", String.valueOf(c.live())));
    }

    /** The furthest step with every step up to it done or not applicable ("invited" at least). */
    static String stage(List<PilotStep> steps) {
        var stage = steps.getFirst().key();
        for (var s : steps) {
            if (!DONE.equals(s.state()) && !NA.equals(s.state())) {
                break;
            }
            if (DONE.equals(s.state())) {
                stage = s.key();
            }
        }
        return stage;
    }

    static @Nullable PilotStep next(List<PilotStep> steps) {
        return steps.stream()
                .filter(s -> !DONE.equals(s.state()) && !NA.equals(s.state()))
                .findFirst()
                .orElse(null);
    }

    private static PilotStep step(
            String key, String state, @Nullable String action, @Nullable String owner, Map<String, String> params) {
        return new PilotStep(key, state, action, owner, Map.copyOf(params));
    }
}
