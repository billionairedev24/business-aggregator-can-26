package ca.northline.merchants.application;

import ca.northline.merchants.api.PilotCohort.Note;
import ca.northline.merchants.domain.PilotInvite;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-120): {@code merchants.pilot_businesses}, its invites and notes, and the businesses' facts. */
public interface PilotStore {

    record PilotRow(
            String id,
            String marketId,
            String businessType,
            String label,
            @Nullable String merchantId,
            @Nullable String ownerId,
            @Nullable String blocker,
            @Nullable String blockerOwner,
            @Nullable Instant blockerSince,
            String createdBy,
            Instant createdAt) {}

    /** What the business's own rows say; {@code categories} are its approved or requested category ids. */
    record Facts(
            String merchantId,
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
            @Nullable String siteVisit,
            @Nullable String siteVisitReference,
            @Nullable Instant submittedAt,
            @Nullable Instant approvedAt,
            boolean storefrontPublished,
            @Nullable String searchHidden,
            List<String> categories) {

        public Facts {
            rejectedChecks = List.copyOf(rejectedChecks);
            categories = List.copyOf(categories);
        }
    }

    void insert(PilotRow row);

    /** Locks the row for an update. */
    Optional<PilotRow> lock(String pilotId);

    Optional<PilotRow> pilot(String pilotId);

    Optional<PilotRow> byMerchant(String merchantId);

    List<PilotRow> pilots(@Nullable String marketId);

    void linkMerchant(String pilotId, String merchantId, Instant at);

    void owner(String pilotId, @Nullable String ownerId, Instant at);

    void blocker(String pilotId, @Nullable String text, @Nullable String owner, @Nullable Instant since, Instant at);

    void insertInvite(PilotInvite invite, String tokenHash);

    /** Newest first. */
    List<PilotInvite> invites(String pilotId);

    Map<String, PilotInvite> latestInvites(Collection<String> pilotIds);

    Optional<PilotInvite> inviteByTokenHash(String hash);

    void acceptInvite(String inviteId, String userId, Instant at);

    /** Withdraws the pilot business's pending (unaccepted, unrevoked) invites. */
    void revokePending(String pilotId, Instant at);

    void insertNote(String pilotId, Note note);

    /** Newest first. */
    List<Note> notes(String pilotId);

    Map<String, Facts> facts(Collection<String> merchantIds);

    /** Hides a business that isn't searchable yet (an applicant) from search before launch, without an event. */
    void hideBeforeLaunch(String merchantId, Instant at);
}
