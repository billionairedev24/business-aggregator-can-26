package ca.northline.uat.api;

import org.jspecify.annotations.Nullable;

/**
 * S-121: is this person a pilot participant — on their own (a customer, a courier, a staff member) or through a
 * business that takes part (any member of it)? The UAT feedback control shows only for them. S-120's pilot cohort
 * (merchants' onboarding pipeline) is a different list; see docs/DECISIONS.md "S-121" for how the two meet.
 */
public interface PilotParticipants {

    /**
     * @param merchantId the business the person acts for (Studio), null elsewhere; counted only when they are on its
     *     team
     */
    boolean isParticipant(String userId, @Nullable String merchantId);
}
