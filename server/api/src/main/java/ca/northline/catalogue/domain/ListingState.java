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
 * </pre>
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class ListingState {
    private Vetting vetting;
    private ListingStatus status;
    private List<VettingFlag> flags;
    private @Nullable Instant submittedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    /** A new, private draft. */
    public static ListingState draft(Instant at) {
        return new ListingState(Vetting.DRAFT, ListingStatus.HIDDEN, List.of(), null, at, at);
    }

    /** Content changed. A listing waiting for vetting is withdrawn back to draft (it must be re-submitted). */
    void edited(Instant at) {
        if (vetting == Vetting.PENDING) {
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

    void submit(Instant at) {
        if (vetting != Vetting.DRAFT && vetting != Vetting.REJECTED) {
            throw new Conflict("not_submittable", ListingMessages.NOT_SUBMITTABLE);
        }
        vetting = Vetting.PENDING;
        flags = List.of();
        submittedAt = at;
        updatedAt = at;
    }

    /** @return true when the checks approved the listing (it is now live); false when it was flagged or not pending. */
    boolean vetted(List<VettingFlag> found, Instant at) {
        if (vetting != Vetting.PENDING) {
            return false;
        }
        updatedAt = at;
        if (found.isEmpty()) {
            vetting = Vetting.APPROVED;
            status = ListingStatus.LIVE;
            flags = List.of();
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
