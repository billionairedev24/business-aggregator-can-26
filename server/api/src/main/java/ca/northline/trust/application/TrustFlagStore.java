package ca.northline.trust.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: trust &amp; safety flags ({@code trust.flags}), raised at most once per target and rule. */
public interface TrustFlagStore {

    void raise(
            String id,
            String targetType,
            String targetId,
            String rule,
            String merchantId,
            String actorId,
            Map<String, String> evidence);

    /**
     * S-133: like {@link #raise}, but only an <em>open</em> flag for the target and rule stops it (a listing submitted
     * again after staff dismissed its flag can be flagged again). True when a flag was raised.
     */
    boolean raiseUnlessOpen(
            String id,
            String targetType,
            String targetId,
            String rule,
            @Nullable String merchantId,
            String actorId,
            Map<String, String> evidence);

    /**
     * S-133 console queue: newest first.
     *
     * @param state {@code open}, {@code dismissed}, {@code actioned}; null for any
     * @param source {@code ai} (raised by the model's screening or scan) or {@code rules}; null for any
     */
    List<StoredFlag> list(@Nullable String state, @Nullable String source, int limit);

    Optional<StoredFlag> find(String id);

    /** S-92: open flags on one kind of target (and one target, when given), oldest first. */
    List<StoredFlag> openOn(String targetType, @Nullable String targetId, int limit);

    /** Records the staff decision on an open flag; false when it isn't open any more. */
    default boolean decide(String id, String state, String staffId, @Nullable String note, Instant at) {
        return decide(id, state, state, staffId, note, at);
    }

    /**
     * S-93: records the decision and what was done ({@code action}: the state itself, or a staff action such as
     * {@code warn}); false when the flag isn't open any more.
     */
    boolean decide(String id, String state, String action, String staffId, @Nullable String note, Instant at);

    /** S-93: open flags (any target) of the businesses in scope, oldest first, then decided since {@code since}. */
    List<StoredFlag> queue(ca.northline.shared.MerchantScope scope, Instant since, int limit);

    record StoredFlag(
            String id,
            String targetType,
            String targetId,
            String rule,
            @Nullable String merchantId,
            String state,
            Map<String, String> evidence,
            Instant createdAt,
            @Nullable String decidedBy,
            @Nullable Instant decidedAt,
            @Nullable String decisionNote,
            @Nullable String action) {}
}
