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
    String NO_BUSINESS = "Only a business can be warned.";

    List<FlagView> list(@Nullable String state, @Nullable String source, int limit);

    /** S-93: open flags of the businesses in scope (oldest first) and those decided in the last 7 days, with names. */
    List<FlagView> queue(ca.northline.shared.MerchantScope scope, int limit);

    /** S-93: a staff action on an open flag (actions it); {@code warn} emails the business's owners. */
    FlagView act(String flagId, TrustRules.FlagAction action, String staffId, String role, @Nullable String note);

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
            @Nullable String decisionNote,
            @Nullable String action,
            @Nullable String businessName,
            @Nullable String province) {}
}
