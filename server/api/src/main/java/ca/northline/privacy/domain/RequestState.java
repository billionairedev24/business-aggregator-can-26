package ca.northline.privacy.domain;

import ca.northline.shared.CodedEnum;
import java.util.EnumSet;
import java.util.Set;

/**
 * A request's life:
 *
 * <pre>
 * awaiting_verification ──verify──▶ verified ──start──▶ in_progress ──▶ completed
 *        │                              │
 *        └──────── withdraw / reject ───┴──▶ withdrawn | rejected
 * </pre>
 *
 * Access starts as soon as it is verified, erasure after its grace period (or when staff start it), a correction when
 * staff apply it ({@code verified → completed}). Nothing leaves {@code in_progress} but completion: an erasure that has
 * begun is not undone.
 */
public enum RequestState implements CodedEnum {
    AWAITING_VERIFICATION,
    VERIFIED,
    IN_PROGRESS,
    COMPLETED,
    REJECTED,
    WITHDRAWN;

    public static final Set<RequestState> OPEN = Set.copyOf(EnumSet.of(AWAITING_VERIFICATION, VERIFIED, IN_PROGRESS));

    public boolean open() {
        return OPEN.contains(this);
    }

    public boolean canMoveTo(RequestState next) {
        return switch (this) {
            case AWAITING_VERIFICATION -> next == VERIFIED || next == WITHDRAWN || next == REJECTED;
            case VERIFIED -> next == IN_PROGRESS || next == COMPLETED || next == WITHDRAWN || next == REJECTED;
            case IN_PROGRESS -> next == COMPLETED;
            case COMPLETED, REJECTED, WITHDRAWN -> false;
        };
    }
}
