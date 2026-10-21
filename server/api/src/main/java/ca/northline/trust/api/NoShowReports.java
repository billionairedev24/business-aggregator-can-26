package ca.northline.trust.api;

/**
 * S-156: no-shows reported on a booking open a trust flag for the console's queue; staff confirm it (S-93's
 * {@code confirm} action) and only confirmed ones count for the rules' consequences. A provider no-show (rule
 * {@code no_show}: the customer reported that nobody came) counts against the business; a customer no-show (rule
 * {@code customer_no_show}: the business reported the customer wasn't there) against the customer. One open flag per
 * booking and rule.
 */
public interface NoShowReports {

    /** The customer reported that the provider didn't come; false when already reported. */
    boolean providerNoShow(String merchantId, String bookingId, String customerId);

    /** The business reported that the customer wasn't there; false when already reported. */
    boolean customerNoShow(String merchantId, String bookingId, String customerId, String reportedBy);
}
