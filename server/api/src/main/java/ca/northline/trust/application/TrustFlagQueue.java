package ca.northline.trust.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * S-133 console queue: trust &amp; safety flags — from the detectors, business reports and the AI assist — each with its
 * explanation; staff decide every one (dismiss or action). Deciding records who, when and why; it changes nothing
 * else (no listing, review, message or business is touched automatically).
 */
public interface TrustFlagQueue {

    String NOT_OPEN = "This flag was already decided.";
    String DECISION_REQUIRED = "Choose dismissed or actioned.";

    List<FlagView> list(@Nullable String state, @Nullable String source, int limit);

    /** @param role the console role(s) the staff member acts with (audit log) */
    FlagView decide(String flagId, String decision, String staffId, String role, @Nullable String note);

    /**
     * @param source "ai" when the model's screening or scan raised it, else "rules"
     * @param explanation why the flag was raised, in words (the model's, or the rule's)
     * @param categories the screening's categories ({@code off_platform_payment}, …) or the scan's signals
     * @param evidence the raw evidence (codes and ids; no message text)
     */
    record FlagView(
            String id,
            String targetType,
            String targetId,
            String rule,
            @Nullable String merchantId,
            String state,
            String source,
            String explanation,
            List<String> categories,
            Map<String, String> evidence,
            Instant createdAt,
            @Nullable String decidedBy,
            @Nullable Instant decidedAt,
            @Nullable String decisionNote) {}
}
