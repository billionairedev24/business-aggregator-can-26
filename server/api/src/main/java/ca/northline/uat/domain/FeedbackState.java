package ca.northline.uat.domain;

import ca.northline.shared.CodedEnum;
import java.util.EnumSet;
import java.util.Set;

/**
 * The triage flow: new → triaged → accepted (blocking or not) → fixed → verified → closed, or won't fix / duplicate.
 * A fix that fails verification goes back to accepted; a closed, won't-fix or duplicate item can be reopened to triaged.
 */
public enum FeedbackState implements CodedEnum {
    NEW,
    TRIAGED,
    ACCEPTED,
    FIXED,
    VERIFIED,
    CLOSED,
    WONT_FIX,
    DUPLICATE;

    /** The states this one may move to. */
    public Set<FeedbackState> next() {
        return switch (this) {
            case NEW -> EnumSet.of(TRIAGED, WONT_FIX, DUPLICATE);
            case TRIAGED -> EnumSet.of(ACCEPTED, WONT_FIX, DUPLICATE);
            // accepted → accepted changes whether it blocks
            case ACCEPTED -> EnumSet.of(ACCEPTED, FIXED, WONT_FIX, DUPLICATE);
            case FIXED -> EnumSet.of(VERIFIED, ACCEPTED);
            case VERIFIED -> EnumSet.of(CLOSED, ACCEPTED);
            case CLOSED, WONT_FIX, DUPLICATE -> EnumSet.of(TRIAGED);
        };
    }

    public boolean canMoveTo(FeedbackState to) {
        return next().contains(to);
    }

    /** Nobody has decided about it yet. */
    public boolean untriaged() {
        return this == NEW || this == TRIAGED;
    }

    /** Work is still to do or to check: a blocking item in one of these holds the launch. */
    public boolean open() {
        return this == NEW || this == TRIAGED || this == ACCEPTED || this == FIXED;
    }

    /** {@code accepted}, {@code fixed}, {@code verified} and {@code closed} carry the blocking decision. */
    public boolean decided() {
        return this == ACCEPTED || this == FIXED || this == VERIFIED || this == CLOSED;
    }
}
