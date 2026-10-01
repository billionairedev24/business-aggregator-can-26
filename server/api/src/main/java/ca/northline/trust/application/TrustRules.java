package ca.northline.trust.application;

import ca.northline.shared.MerchantScope;
import ca.northline.trust.domain.TrustRule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** S-93: the trust &amp; safety rules as configuration, the rating floor's impact, and staff actions on flags. */
public interface TrustRules {

    String RULE_UNKNOWN = "Pick a rule from the list.";
    String ACTION_REQUIRED = "Choose an action.";

    /** Every rule with its current value (the default when never edited). */
    List<RuleView> rules();

    RuleView update(TrustRule rule, Map<String, ?> value, String staffId, String role);

    /** How many businesses with at least {@code minReviews} reviews in the window are below {@code rating}. */
    Impact ratingFloorImpact(double rating, MerchantScope scope);

    /**
     * @param edited when staff last changed it; null = the default
     * @param fields the value's fields with their kind and range (the console builds its form from them)
     */
    record RuleView(
            String key,
            Map<String, Object> value,
            Map<String, Object> defaults,
            List<TrustRule.Field> fields,
            @Nullable String updatedBy,
            @Nullable Instant edited) {}

    /**
     * @param affected businesses below the floor
     * @param total businesses with enough reviews to be rated
     */
    record Impact(double rating, int days, long affected, long total) {}

    /** Staff actions on a flag ({@code trust.flags.action}); each actions the flag. */
    enum FlagAction implements ca.northline.shared.CodedEnum {
        /** Email the business a warning (off-platform payment, first time). */
        WARN,
        /** Start the coaching plan (below a floor). */
        COACH,
        /** Confirm a customer's no-show (reliability drops). */
        CONFIRM,
        /** Suspend the business's listing rights (needs the {@code suspend} action). */
        SUSPEND_LISTINGS,
        /** Hand it to the operations on-call. */
        ESCALATE
    }
}
