package ca.northline.golive.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * A request to switch a market from {@code pilot} to {@code live} (S-118, the two-person rule): one admin requests,
 * <em>another</em> approves; the requester can withdraw it, another admin can reject it, and it lapses after a while
 * (the checklist it was asked on is stale by then). An override — launching while required gates don't clear — needs a
 * written reason and is visible to the approver.
 *
 * @param blocking the required gates that didn't clear when it was requested
 */
@With
public record LaunchRequest(
        String id,
        String marketId,
        State state,
        String requestedBy,
        Instant requestedAt,
        Instant expiresAt,
        @Nullable String note,
        boolean override,
        @Nullable String overrideReason,
        List<String> blocking,
        @Nullable String decidedBy,
        @Nullable Instant decidedAt,
        @Nullable String decisionNote) {

    public static final String NOTE = "Keep the note to 500 characters.";
    public static final String OVERRIDE_REASON = "Give the reason for the emergency override, 20 to 500 characters.";
    public static final String SAME_PERSON = "A second admin must approve: you requested this launch.";
    public static final String CLOSED = "This launch request was already decided.";
    public static final String EXPIRED = "This launch request expired. Ask again from the checklist.";

    public enum State implements CodedEnum {
        PENDING,
        APPROVED,
        REJECTED,
        WITHDRAWN,
        EXPIRED
    }

    public LaunchRequest {
        blocking = List.copyOf(blocking);
    }

    /** A new request; {@code overrideReason} non-blank = an emergency override. */
    public static LaunchRequest open(
            String id,
            String marketId,
            String by,
            Instant at,
            Duration ttl,
            @Nullable String note,
            @Nullable String overrideReason,
            List<String> blocking) {
        var text = blankToNull(note);
        if (text != null && text.length() > 500) {
            throw RuleViolation.of("note", "length", NOTE);
        }
        var reason = blankToNull(overrideReason);
        if (reason != null && (reason.length() < 20 || reason.length() > 500)) {
            throw RuleViolation.of("overrideReason", "length", OVERRIDE_REASON);
        }
        return new LaunchRequest(
                id,
                marketId,
                State.PENDING,
                by,
                at,
                at.plus(ttl),
                text,
                reason != null,
                reason,
                blocking,
                null,
                null,
                null);
    }

    public boolean pending() {
        return state == State.PENDING;
    }

    public boolean lapsed(Instant now) {
        return pending() && !now.isBefore(expiresAt);
    }

    /** The second admin approves. @throws Conflict when decided, lapsed, or asked by the requester */
    public LaunchRequest approve(String by, Instant at) {
        open(at);
        if (by.equals(requestedBy)) {
            throw new Conflict("same_person", SAME_PERSON);
        }
        return withState(State.APPROVED).withDecidedBy(by).withDecidedAt(at);
    }

    /** Withdrawn by the requester, rejected by anyone else. */
    public LaunchRequest close(String by, @Nullable String note, Instant at) {
        open(at);
        var text = blankToNull(note);
        if (text != null && text.length() > 500) {
            throw RuleViolation.of("reason", "length", NOTE);
        }
        return withState(by.equals(requestedBy) ? State.WITHDRAWN : State.REJECTED)
                .withDecidedBy(by)
                .withDecidedAt(at)
                .withDecisionNote(text);
    }

    public LaunchRequest expire() {
        return withState(State.EXPIRED).withDecidedAt(expiresAt);
    }

    private void open(Instant at) {
        if (!pending()) {
            throw new Conflict("request_closed", CLOSED);
        }
        if (lapsed(at)) {
            throw new Conflict("request_expired", EXPIRED);
        }
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
