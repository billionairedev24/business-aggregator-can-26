package ca.northline.uat.domain;

import ca.northline.shared.CodedEnum;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The launch verdict from UAT (S-121; S-118's go-live checklist reads it). "Go" needs all of:
 *
 * <ul>
 *   <li>no blocking item open (accepted as blocking and not fixed) and none fixed but not verified yet;
 *   <li>no participant-reported blocker still waiting for triage;
 *   <li>every persona has at least one active participant, and every active participant has signed off their persona's
 *       script, with or without comments — none blocked, none pending.
 * </ul>
 */
public final class GoNoGo {

    private GoNoGo() {}

    /** Why it is not a go yet (codes; the console words them in English or French). */
    public enum ReasonCode implements CodedEnum {
        BLOCKING_OPEN,
        BLOCKING_UNVERIFIED,
        BLOCKERS_UNTRIAGED,
        NO_PARTICIPANTS,
        SIGNOFFS_PENDING,
        SIGNOFFS_BLOCKED
    }

    /** @param persona the persona it concerns, null for the whole pilot */
    public record Reason(
            ReasonCode code, int count, @Nullable Persona persona) {}

    /** One persona's sign-off coverage: active participants and where their latest sign-off stands. */
    public record Coverage(Persona persona, int participants, int signedOff, int withComments, int blocked) {

        public int pending() {
            return participants - signedOff - withComments - blocked;
        }

        public boolean complete() {
            return participants > 0 && pending() == 0 && blocked == 0;
        }
    }

    public record Verdict(boolean go, List<Reason> reasons) {
        public Verdict {
            reasons = List.copyOf(reasons);
        }
    }

    public static Verdict decide(
            int blockingOpen, int blockingUnverified, int untriagedBlockers, List<Coverage> coverage) {
        var reasons = new ArrayList<Reason>();
        if (blockingOpen > 0) {
            reasons.add(new Reason(ReasonCode.BLOCKING_OPEN, blockingOpen, null));
        }
        if (blockingUnverified > 0) {
            reasons.add(new Reason(ReasonCode.BLOCKING_UNVERIFIED, blockingUnverified, null));
        }
        if (untriagedBlockers > 0) {
            reasons.add(new Reason(ReasonCode.BLOCKERS_UNTRIAGED, untriagedBlockers, null));
        }
        for (var c : coverage) {
            if (c.participants() == 0) {
                reasons.add(new Reason(ReasonCode.NO_PARTICIPANTS, 0, c.persona()));
                continue;
            }
            if (c.blocked() > 0) {
                reasons.add(new Reason(ReasonCode.SIGNOFFS_BLOCKED, c.blocked(), c.persona()));
            }
            if (c.pending() > 0) {
                reasons.add(new Reason(ReasonCode.SIGNOFFS_PENDING, c.pending(), c.persona()));
            }
        }
        return new Verdict(reasons.isEmpty(), reasons);
    }
}
