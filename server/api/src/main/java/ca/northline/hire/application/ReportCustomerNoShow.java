package ca.northline.hire.application;

/**
 * S-156, Studio › Appointments: "The customer didn't show". Opens a {@code customer_no_show} trust flag for staff to
 * confirm (S-93); confirmed no-shows count for the customer's consequences. Possible 15 minutes after the start, on a
 * booking that is confirmed, en route or on site (the help article's rule).
 */
public interface ReportCustomerNoShow {

    String TOO_EARLY = "You can report a no-show 15 minutes after the start time.";
    String NOT_REPORTABLE = "Only an upcoming or ongoing job can be reported as a no-show.";
    String ALREADY = "This no-show is already with Northline.";

    void report(String merchantId, String bookingId, String userId);
}
