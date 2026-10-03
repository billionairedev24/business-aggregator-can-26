package ca.northline.uat.api;

import org.jspecify.annotations.Nullable;

/**
 * S-121: is this person a pilot participant — on their own (a customer, a courier, a staff member: UAT's list) or
 * through a business of S-120's pilot cohort ({@code merchants.api.PilotCohort}, any member of it)? The UAT feedback
 * control shows only for them.
 */
public interface PilotParticipants {

    /**
     * @param merchantId the business the person acts for (Studio), null elsewhere; counted only when they are on its
     *     team
     */
    boolean isParticipant(String userId, @Nullable String merchantId);
}
