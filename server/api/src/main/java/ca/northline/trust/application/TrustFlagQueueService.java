package ca.northline.trust.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.FlagDecided;
import ca.northline.trust.api.ListingFlags;
import ca.northline.trust.application.TrustFlagStore.StoredFlag;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link TrustFlagQueue} over {@code trust.flags}; every decision is audited ({@code trust.flag_decided}). */
@Service
@RequiredArgsConstructor
class TrustFlagQueueService implements TrustFlagQueue, ListingFlags {

    static final String LISTING = "listing";

    private final TrustFlagStore flags;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<FlagView> list(@Nullable String state, @Nullable String source, int limit) {
        return flags.list(state, source, Math.clamp(limit, 1, 200)).stream()
                .map(TrustFlagQueueService::view)
                .toList();
    }

    @Override
    @Transactional
    public FlagView decide(String flagId, String decision, String staffId, String role, @Nullable String note) {
        if (!"dismissed".equals(decision) && !"actioned".equals(decision)) {
            throw RuleViolation.of("decision", "invalid", DECISION_REQUIRED);
        }
        var flag = flags.find(flagId).orElseThrow(() -> new NotFound("flag", flagId));
        if (!record(flag, decision, staffId, role, note)) {
            throw new Conflict("flag_decided", NOT_OPEN);
        }
        return view(flags.find(flagId).orElseThrow(() -> new NotFound("flag", flagId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListingFlag> open(int limit) {
        return flags.openOn(LISTING, null, Math.clamp(limit, 1, 500)).stream()
                .map(f -> {
                    var v = view(f);
                    return new ListingFlag(
                            f.id(),
                            f.targetId(),
                            f.merchantId(),
                            f.rule(),
                            v.source(),
                            v.explanation(),
                            v.categories(),
                            f.createdAt());
                })
                .toList();
    }

    @Override
    @Transactional
    public int resolve(String listingId, boolean actioned, String staffId, String role, @Nullable String note) {
        var decided = 0;
        for (var flag : flags.openOn(LISTING, listingId, 100)) {
            if (record(flag, actioned ? "actioned" : "dismissed", staffId, role, note)) {
                decided++;
            }
        }
        return decided;
    }

    /** Decides one open flag: the row, the audit entry and {@link FlagDecided}. False when it was no longer open. */
    private boolean record(StoredFlag flag, String decision, String staffId, String role, @Nullable String note) {
        var flagId = flag.id();
        var cleanNote = note == null || note.isBlank() ? null : note.strip();
        var now = clock.instant();
        if (!flags.decide(flagId, decision, staffId, cleanNote, now)) {
            return false;
        }
        // Platform-level entry (no merchant_id): staff moderation isn't listed in the business's own audit log.
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                role,
                "trust.flag_decided",
                "trust_flag",
                flagId,
                Map.of("state", flag.state()),
                Map.of(
                        "state",
                        decision,
                        "rule",
                        flag.rule(),
                        "source",
                        source(flag),
                        "merchantId",
                        String.valueOf(flag.merchantId()))));
        events.publishEvent(new FlagDecided(
                Ids.next(),
                now,
                flagId,
                flag.targetType(),
                flag.targetId(),
                flag.rule(),
                flag.merchantId(),
                decision,
                staffId,
                role,
                cleanNote));
        return true;
    }

    static FlagView view(StoredFlag f) {
        var e = f.evidence();
        var categories = e.getOrDefault("categories", e.getOrDefault("signals", ""));
        return new FlagView(
                f.id(),
                f.targetType(),
                f.targetId(),
                f.rule(),
                f.merchantId(),
                f.state(),
                source(f),
                explanation(f),
                categories.isBlank()
                        ? List.of()
                        : Arrays.stream(categories.split(","))
                                .map(String::strip)
                                .toList(),
                e,
                f.createdAt(),
                f.decidedBy(),
                f.decidedAt(),
                f.decisionNote());
    }

    private static String source(StoredFlag f) {
        return "ai".equals(f.evidence().get("source")) ? "ai" : "rules";
    }

    /** Every flag explains itself: the evidence's explanation, else what the rule means. */
    static String explanation(StoredFlag f) {
        var given = f.evidence().get("explanation");
        if (given != null && !given.isBlank()) {
            return given;
        }
        return switch (f.rule()) {
            case "off_platform_payment" ->
                "The message detector found masked contact details or words about paying outside Northline.";
            case "review_report" ->
                "The business reported this review"
                        + (f.evidence().containsKey("reason")
                                ? " as " + f.evidence().get("reason").replace('_', ' ')
                                : "")
                        + ".";
            default -> "Raised by the " + f.rule().replace('_', ' ') + " rule.";
        };
    }
}
