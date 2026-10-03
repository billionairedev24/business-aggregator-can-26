package ca.northline.console.application;

import ca.northline.merchants.api.KitchenVisits;
import ca.northline.merchants.api.PilotCohort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Platform console — Pilot onboarding (S-120): the pipeline of a market's pilot businesses, from the invite to live.
 * Each business's stage is derived from the records of the modules that own each step (merchants: onboarding,
 * checklist, kitchen visit, approval, page; payments: Stripe Connect as Stripe reported it; catalogue and food: the
 * listings) — nothing is copied. The writes (invites, enrolment, owners, blockers, notes, kitchen visits) are the
 * merchants module's {@link PilotCohort} and {@link KitchenVisits}.
 */
public interface PilotOnboarding {

    /** The steps, in order (docs/runbooks/pilot-onboarding.md § Stages). */
    List<String> STEPS = List.of(
            "invited",
            "account_created",
            "details_complete",
            "identity_verified",
            "stripe_ready",
            "kitchen_visit",
            "catalogue_ready",
            "approved",
            "live");

    /** @param marketId null = every market */
    PilotBoard board(@Nullable String marketId);

    /** The board as CSV, one business per line (codes, not words: the sheet is for people and scripts alike). */
    String csv(@Nullable String marketId);

    PilotDetail detail(String pilotId);

    /** A market a pilot can be in, with its stage ({@code off|waitlist|pilot|live}). */
    record PilotMarket(String id, String city, String province, String stage) {}

    /**
     * @param stages how many businesses reached each stage (the furthest step all earlier ones are done)
     * @param live businesses live
     * @param blocked businesses with a blocker (written down or found)
     */
    record PilotBoard(
            List<PilotMarket> markets,
            @Nullable String marketId,
            Map<String, Integer> stages,
            int live,
            int blocked,
            List<PilotRow> items) {}

    /**
     * One step of a business's checklist.
     *
     * @param state {@code done | todo | waiting | blocked | na}
     * @param action what has to happen next, a code the console words ({@code accept_invite}, {@code schedule_visit} …)
     * @param owner who has to act: {@code business | northline | stripe | inspector}
     * @param params values the words need ({@code expiresAt}, {@code at}, {@code complete}, {@code total} …)
     */
    record PilotStep(
            String key,
            String state,
            @Nullable String action,
            @Nullable String owner,
            Map<String, String> params) {}

    /**
     * @param stage the furthest step reached (every earlier step done or not applicable)
     * @param next the first step not done, with its action; null once live
     * @param businessName the business's own name once it has one, else the working label
     * @param city the market the business trades in (its city), null while it has none
     */
    record PilotRow(
            String id,
            String label,
            String businessName,
            String businessType,
            String marketId,
            @Nullable String merchantId,
            @Nullable String city,
            String stage,
            @Nullable PilotStep next,
            List<PilotStep> checklist,
            boolean blocked,
            @Nullable String blocker,
            @Nullable String blockerOwner,
            @Nullable Instant blockerSince,
            @Nullable String ownerId,
            @Nullable String ownerName,
            @Nullable String inviteState,
            int listings,
            int listingsLive,
            Instant createdAt) {}

    record PilotNoteView(
            String id, String authorId, @Nullable String authorName, String body, Instant createdAt) {}

    record PilotDetail(
            PilotRow row,
            List<PilotNoteView> notes,
            List<PilotCohort.Invite> invites,
            List<KitchenVisits.Visit> visits,
            List<String> visitItems,
            boolean kitchenVisitRequired) {}
}
