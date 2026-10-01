package ca.northline.trust.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.application.TrustFlagStore.StoredFlag;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link TrustFlagQueue} over {@code trust.flags}; every decision is audited ({@code trust.flag_decided}). */
@Service
@RequiredArgsConstructor
class TrustFlagQueueService implements TrustFlagQueue {

    private final TrustFlagStore flags;
    private final AuditTrail audit;
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
    public FlagView decide(String flagId, String decision, String staffId, @Nullable String note) {
        if (!"dismissed".equals(decision) && !"actioned".equals(decision)) {
            throw RuleViolation.of("decision", "invalid", DECISION_REQUIRED);
        }
        var flag = flags.find(flagId).orElseThrow(() -> new NotFound("flag", flagId));
        var cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (!flags.decide(flagId, decision, staffId, cleanNote, clock.instant())) {
            throw new Conflict("flag_decided", NOT_OPEN);
        }
        // Platform-level entry (no merchant_id): staff moderation isn't listed in the business's own audit log.
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                "staff",
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
        return view(flags.find(flagId).orElseThrow(() -> new NotFound("flag", flagId)));
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
