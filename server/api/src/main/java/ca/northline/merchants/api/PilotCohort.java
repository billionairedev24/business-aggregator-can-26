package ca.northline.merchants.api;

import ca.northline.merchants.api.KitchenVisits.Visit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-120: the pilot cohort of a market — businesses staff invited (or enrolled) to onboard before the market opens. The
 * merchants module keeps what only it knows (the pilot row, invites, notes, the blocker staff wrote down, the
 * business's onboarding facts); the console composes the pipeline stage from these and the catalogue's, the menu's and
 * Stripe's state. Every change is in the platform audit log.
 */
public interface PilotCohort {

    String MARKET_REQUIRED = "Choose a market that is open for onboarding.";
    String TYPE_REQUIRED = "Choose provider, seller, both or kitchen.";
    String LABEL_REQUIRED = "Enter a working name, 1 to 80 characters.";
    String EMAIL_REQUIRED = "Enter the business's email address.";
    String EMAIL_FORMAT = "That doesn't look like an email address.";
    String LANGUAGE_REQUIRED = "Choose English or French.";
    String BLOCKER_REQUIRED = "Describe the blocker in 1 to 300 characters.";
    String BLOCKER_OWNER_REQUIRED = "Choose who has to act: the business, Northline, Stripe or the inspector.";
    String NOTE_REQUIRED = "Write the note, 1 to 2,000 characters.";
    String OWNER_NOT_STAFF = "Choose someone on the Northline team.";
    String ALREADY_PILOT = "This business is already in a pilot.";
    String ALREADY_ONBOARDING = "This business already has an account; there is nothing to invite to.";

    /** Pilot businesses of a market (every market when null), oldest first. */
    List<Pilot> list(@Nullable String marketId);

    Optional<Pilot> find(String pilotId);

    /** A new pilot business with a signed, expiring invite link, emailed in {@code language}. */
    Invited invite(NewInvite command, Actor actor);

    /** A new link for a business that hasn't started yet; the previous one stops working. */
    Invited reinvite(String pilotId, @Nullable String email, String language, Actor actor);

    /** Marks a business that already exists as a pilot participant of a market. */
    Pilot enrol(String merchantId, String marketId, Actor actor);

    /** Who at Northline looks after it ({@code null} = nobody). */
    Pilot assign(String pilotId, @Nullable String ownerId, Actor actor);

    /** Writes down what holds it up and who has to act; {@code text == null} clears it. */
    Pilot block(String pilotId, @Nullable String text, @Nullable String blockerOwner, Actor actor);

    Note note(String pilotId, String body, Actor actor);

    List<Note> notes(String pilotId);

    List<Invite> invites(String pilotId);

    /**
     * The market went live: its pilot businesses that were hidden from search before launch are shown. Returns how
     * many.
     */
    int marketLaunched(String marketId, Actor actor);

    /** @param role the console role(s) acted with, for the audit log */
    record Actor(String userId, String role) {}

    /** @param language {@code en} or {@code fr}: the email's language */
    record NewInvite(
            String marketId,
            String businessType,
            String label,
            String email,
            String language,
            @Nullable String ownerId) {}

    record Invited(Pilot pilot, String link, Instant expiresAt) {}

    /**
     * @param businessType {@code provider|seller|kitchen|both}
     * @param blockerOwner {@code business|northline|stripe|inspector}
     * @param invite the latest invite, null for an enrolled business
     * @param business the business's onboarding facts once it exists
     */
    record Pilot(
            String id,
            String marketId,
            String businessType,
            String label,
            @Nullable String merchantId,
            @Nullable String ownerId,
            @Nullable String blocker,
            @Nullable String blockerOwner,
            @Nullable Instant blockerSince,
            Instant createdAt,
            @Nullable Invite invite,
            @Nullable Business business) {}

    /** @param state {@code pending|expired|accepted|revoked} */
    record Invite(
            String id,
            String email,
            Instant createdAt,
            Instant expiresAt,
            @Nullable Instant acceptedAt,
            String state) {}

    /**
     * What the business's own records say (nothing is copied into the pilot row).
     *
     * @param status {@code applicant|pending|active|paused|suspended}
     * @param detailsComplete the Business step is saved (legal name, structure, categories)
     * @param identity the {@code kyc} row's status ({@code todo|submitted|verified|rejected}), null without one
     * @param identityInReview an owner's Stripe Identity result waits for an agent
     * @param rejectedChecks check keys an agent sent back or a registry refused
     * @param kitchenVisitRequired region or category rule (kitchens only)
     * @param siteVisit the {@code site_visit} row's status, null when the business has none
     * @param siteVisitSlot the slot the owner booked, when they did
     * @param visit the latest kitchen visit
     * @param searchHidden why it is hidden from search ({@code pilot} before launch), null when shown
     */
    record Business(
            String displayName,
            String type,
            String status,
            String onboardingStep,
            @Nullable String province,
            @Nullable String city,
            boolean detailsComplete,
            int checksComplete,
            int checksTotal,
            List<String> rejectedChecks,
            @Nullable String identity,
            boolean identityInReview,
            int registryReviewsOpen,
            boolean kitchenVisitRequired,
            @Nullable String siteVisit,
            @Nullable Instant siteVisitSlot,
            @Nullable Visit visit,
            @Nullable Instant submittedAt,
            @Nullable Instant approvedAt,
            boolean storefrontPublished,
            @Nullable String searchHidden) {

        public Business {
            rejectedChecks = List.copyOf(rejectedChecks);
        }
    }

    record Note(String id, String authorId, String body, Instant createdAt) {}
}
