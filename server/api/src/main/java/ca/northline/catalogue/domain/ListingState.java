package ca.northline.catalogue.domain;

import ca.northline.shared.Conflict;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * Vetting and visibility of one listing (the part services and product offers share). Customers see a listing only
 * when it is {@link Vetting#APPROVED approved} and {@link ListingStatus#LIVE live}.
 *
 * <pre>
 *   draft ──submit──▶ pending ──checks pass──▶ approved (+ live)
 *     ▲                  │ checks flag ──▶ stays pending with flags (console review) ──▶ approved | rejected
 *     └──── edit ────────┘                                                  rejected ──submit──▶ pending
 *
 *   approved ──price / category / images change (S-39)──▶ pending (revetReasons) ──checks pass──▶ approved
 * </pre>
 *
 * A re-vetted listing keeps the merchant's live / hidden choice, and an edit while it is being re-vetted doesn't
 * withdraw it to draft.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class ListingState {
    private Vetting vetting;
    private ListingStatus status;
    private List<VettingFlag> flags;
    private @Nullable Instant submittedAt;
    /** S-39: why an approved listing is back in vetting; empty unless it is being re-vetted. */
    @Builder.Default
    private List<MaterialField> revetReasons = List.of();

    private final Instant createdAt;
    private Instant updatedAt;

    /** A new, private draft. */
    public static ListingState draft(Instant at) {
        return new ListingState(Vetting.DRAFT, ListingStatus.HIDDEN, List.of(), null, List.of(), at, at);
    }

    /**
     * Content changed. A listing waiting for its first vetting is withdrawn back to draft (it must be re-submitted); one
     * being re-vetted stays pending.
     */
    void edited(Instant at) {
        if (vetting == Vetting.PENDING && !isRevetting()) {
            vetting = Vetting.DRAFT;
            submittedAt = null;
            flags = List.of();
        }
        updatedAt = at;
    }

    /** A change that doesn't affect vetting (price / stock quick update). */
    void touched(Instant at) {
        updatedAt = at;
    }

    /**
     * S-39: material fields of an approved listing (or of one already being re-vetted) changed, so it goes back to
     * pending for the automated checks. The live / hidden choice is kept.
     *
     * @return true when the checks must run
     */
    boolean revet(java.util.Set<MaterialField> changed, Instant at) {
        if (changed.isEmpty() || (vetting != Vetting.APPROVED && !isRevetting())) {
            return false;
        }
        var reasons = java.util.EnumSet.copyOf(changed);
        reasons.addAll(revetReasons);
        vetting = Vetting.PENDING;
        flags = List.of();
        submittedAt = at;
        updatedAt = at;
        revetReasons = MaterialField.sorted(reasons);
        return true;
    }

    public boolean isRevetting() {
        return vetting == Vetting.PENDING && !revetReasons.isEmpty();
    }

    void submit(Instant at) {
        if (vetting != Vetting.DRAFT && vetting != Vetting.REJECTED) {
            throw new Conflict("not_submittable", ListingMessages.NOT_SUBMITTABLE);
        }
        vetting = Vetting.PENDING;
        flags = List.of();
        revetReasons = List.of();
        submittedAt = at;
        updatedAt = at;
    }

    /**
     * The automated checks' result for a pending listing. Approval makes a first submission live; a re-vetted listing
     * keeps its live / hidden choice.
     *
     * @return true when the checks approved the listing; false when it was flagged or not pending
     */
    boolean vetted(List<VettingFlag> found, Instant at) {
        if (vetting != Vetting.PENDING) {
            return false;
        }
        updatedAt = at;
        if (found.isEmpty()) {
            if (!isRevetting()) {
                status = ListingStatus.LIVE;
            }
            vetting = Vetting.APPROVED;
            flags = List.of();
            revetReasons = List.of();
            return true;
        }
        flags = List.copyOf(found);
        return false;
    }

    /** @return true when the listing became visible to customers */
    boolean publish(Instant at) {
        if (vetting == Vetting.DRAFT) {
            throw new Conflict("listing_draft", ListingMessages.DRAFT_CANNOT_PUBLISH);
        }
        if (status == ListingStatus.LIVE) {
            return false;
        }
        status = ListingStatus.LIVE;
        updatedAt = at;
        return vetting == Vetting.APPROVED;
    }

    /** @return true when the listing stopped being visible to customers */
    boolean hide(Instant at) {
        if (status == ListingStatus.HIDDEN) {
            return false;
        }
        var wasVisible = isCustomerVisible();
        status = ListingStatus.HIDDEN;
        updatedAt = at;
        return wasVisible;
    }

    public boolean isCustomerVisible() {
        return vetting == Vetting.APPROVED && status == ListingStatus.LIVE;
    }
}
