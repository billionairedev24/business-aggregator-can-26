package ca.northline.hire.application;

/**
 * S-153: the business writes to the customer first — about a quote request (before quoting: "Which year is the
 * car?") or about a job. Opens (or finds) the same thread the customer's "Ask a question" opens; the Studio then shows
 * it in Messages ({@code ?thread=<id>}).
 */
public interface OpenCustomerThread {

    /** @return the thread id */
    String forQuoteRequest(String merchantId, String requestId);

    /** @return the thread id */
    String forJob(String merchantId, String bookingId);
}
