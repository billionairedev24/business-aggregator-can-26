package ca.northline.merchants.application;

import ca.northline.merchants.api.SellerDirectory.Oversight;

/**
 * Staff oversight of a business from the console's seller detail (S-82): suspend, reinstate, require re-verification
 * of a check, change tier. Each takes the reason the business is told, is audited ({@code developer.audit_log}) and
 * notifies the business's owners (an event the messaging module emails).
 */
public interface SellerOversight {

    String REASON_REQUIRED = "Give the reason the business will see.";
    String REASON_LENGTH = "Keep the reason under 500 characters.";
    String TIER = "Choose Registered, Trusted or Master.";
    String CHECK_REQUIRED = "Choose the check to verify again.";
    String NOT_ACTIVE = "Only an active or paused business can be suspended.";
    String NOT_SUSPENDED = "This business isn't suspended.";
    String SAME_TIER = "The business is already on that tier.";
    String NOT_APPROVED = "Only an approved business has a tier to change.";
    String NOT_VERIFIABLE = "Only a verified or submitted check can be asked for again.";

    Oversight suspend(String merchantId, String reason, Actor actor);

    Oversight reinstate(String merchantId, String reason, Actor actor);

    Oversight requireReverification(String merchantId, String verificationId, String reason, Actor actor);

    /** @param tier {@code registered} | {@code trusted} | {@code master} */
    Oversight changeTier(String merchantId, String tier, String reason, Actor actor);

    /** @param role the console roles acted with ({@code CurrentStaff.roleCodes()}) */
    record Actor(String userId, String role) {}
}
