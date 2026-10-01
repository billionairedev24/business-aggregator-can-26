package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * One registry lookup and its evidence ({@code merchants.registry_checks}): what was asked, what the source answered,
 * when, the provider's reference, and — when it didn't match — the manual review an agent decides.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class RegistryCheck {

    public enum Trigger implements CodedEnum {
        INITIAL,
        RECHECK
    }

    public enum ReviewState implements CodedEnum {
        OPEN,
        APPROVED,
        REJECTED
    }

    /** What a source answered, before it is compared with what the owner entered. */
    public sealed interface Answer {
        /** @param reference the provider's evidence: record URL, search id, dataset row */
        record Found(RegistryRecord record, @Nullable String reference) implements Answer {}

        record NotFound(@Nullable String reference) implements Answer {}

        /** No API, or the provider answers later: an agent looks it up. */
        record Manual(@Nullable String reference) implements Answer {}

        record Unavailable(String detail) implements Answer {}
    }

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String merchantId;
    private final String verificationId;
    private final RegistrySource source;
    private final RegistrySubject subject;
    private final @Nullable String registry;
    private final String queryNumber;
    private final @Nullable String expectedName;
    private final Trigger trigger;

    @ToString.Include
    private final RegistryOutcome outcome;

    private final List<String> reasons;
    private final @Nullable String recordName;
    private final @Nullable String recordNumber;
    private final @Nullable String recordStatus;
    private final @Nullable LocalDate recordExpiresOn;
    private final @Nullable String reference;
    private final Instant checkedAt;
    private @Nullable ReviewState reviewState;
    private @Nullable String reviewedBy;
    private @Nullable Instant reviewedAt;
    private @Nullable String reviewNote;

    /**
     * Compares the answer with the query: a found record matches when a name matches, it is active (or the source
     * doesn't say) and it hasn't expired (on the day in {@code zone}, the business's). Anything else opens a manual
     * review.
     */
    public static RegistryCheck of(
            String id,
            String merchantId,
            String verificationId,
            RegistryQuery query,
            Answer answer,
            Trigger trigger,
            Instant at,
            ZoneId zone) {
        var reasons = new ArrayList<String>();
        RegistryOutcome outcome;
        RegistryRecord found = null;
        String reference;
        switch (answer) {
            case Answer.Found f -> {
                found = f.record();
                reference = f.reference();
                if (!BusinessNames.matches(query.expectedNames(), found.name())) {
                    reasons.add("name");
                }
                if (found.standing() == RegistryRecord.Standing.INACTIVE) {
                    reasons.add("status");
                }
                if (found.expiresOn() != null && found.expiresOn().isBefore(LocalDate.ofInstant(at, zone))) {
                    reasons.add("expired");
                }
                outcome = reasons.isEmpty() ? RegistryOutcome.MATCHED : RegistryOutcome.MISMATCH;
            }
            case Answer.NotFound n -> {
                reference = n.reference();
                outcome = RegistryOutcome.NOT_FOUND;
            }
            case Answer.Manual m -> {
                reference = m.reference();
                outcome = RegistryOutcome.MANUAL;
            }
            case Answer.Unavailable u -> {
                reference = null;
                reasons.add(u.detail().length() > 200 ? u.detail().substring(0, 200) : u.detail());
                outcome = RegistryOutcome.UNAVAILABLE;
            }
        }
        return new RegistryCheck(
                id,
                merchantId,
                verificationId,
                query.source(),
                query.subject(),
                query.registry(),
                query.number(),
                query.expectedName(),
                trigger,
                outcome,
                List.copyOf(reasons),
                found == null ? null : found.name(),
                found == null ? null : found.number(),
                found == null ? null : found.rawStatus(),
                found == null ? null : found.expiresOn(),
                reference,
                at,
                // a re-check that couldn't reach the source tries again tomorrow instead of bothering an agent
                outcome == RegistryOutcome.MATCHED
                                || (outcome == RegistryOutcome.UNAVAILABLE && trigger == Trigger.RECHECK)
                        ? null
                        : ReviewState.OPEN,
                null,
                null,
                null);
    }

    public boolean matched() {
        return outcome == RegistryOutcome.MATCHED;
    }

    /** An agent decided the review: approved = the evidence is good after all; rejected = it isn't. */
    public void decide(boolean approve, String agentId, @Nullable String note, Instant at) {
        if (reviewState != ReviewState.OPEN) {
            throw new Conflict("review_closed", "This review was already decided.");
        }
        reviewState = approve ? ReviewState.APPROVED : ReviewState.REJECTED;
        reviewedBy = agentId;
        reviewedAt = at;
        reviewNote = note == null || note.isBlank() ? null : note.strip();
    }
}
