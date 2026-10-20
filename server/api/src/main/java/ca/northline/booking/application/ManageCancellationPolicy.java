package ca.northline.booking.application;

import ca.northline.booking.api.CancellationPolicies.Policy;
import ca.northline.region.api.CancellationRules.Rules;

/** S-153, Studio › Availability: the business's late-cancellation policy within its region's limits. */
public interface ManageCancellationPolicy {

    String WINDOW = "Choose a window from 0 to %d hours.";
    String FEE = "Choose a fee from 0 to %d percent.";

    /**
     * @param policy what customers see now
     * @param custom the business set its own (else the region's default applies)
     * @param region the region's default and limits
     */
    record View(Policy policy, boolean custom, Rules region) {}

    View view(String merchantId);

    View set(String merchantId, String actorId, int windowHours, int feeBps);

    /** Back to the region's default. */
    View reset(String merchantId);
}
