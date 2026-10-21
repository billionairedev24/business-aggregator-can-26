package ca.northline.hire.web;

import static ca.northline.shared.security.MerchantPermission.OPERATE;

import ca.northline.hire.application.OpenCustomerThread;
import ca.northline.hire.application.ReportCustomerNoShow;
import ca.northline.shared.security.RequiresMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-153, Studio: "Message the customer" from a quote request or a job card; the answer is the thread to open in
 * Messages. (In {@code hire}, not {@code booking}: messaging already depends on booking through payments.) S-156: the
 * customer no-show report (trust also depends on booking).
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class StudioCustomerThreadController {

    private final OpenCustomerThread threads;
    private final ReportCustomerNoShow noShows;

    record ThreadRef(String threadId) {}

    @PostMapping("/quote-requests/{requestId}/thread")
    @RequiresMerchant(OPERATE)
    ThreadRef quoteRequest(@PathVariable String merchantId, @PathVariable String requestId) {
        return new ThreadRef(threads.forQuoteRequest(merchantId, requestId));
    }

    /**
     * S-156: the customer wasn't there — 204; 409 {@code too_early} (before start + 15 min), {@code not_reportable},
     * {@code already_reported}.
     */
    @PostMapping("/jobs/{jobId}/customer-no-show")
    @RequiresMerchant(OPERATE)
    org.springframework.http.ResponseEntity<Void> customerNoShow(
            @PathVariable String merchantId,
            @PathVariable String jobId,
            ca.northline.shared.security.CurrentMember member) {
        noShows.report(merchantId, jobId, member.userId());
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/jobs/{jobId}/thread")
    @RequiresMerchant(OPERATE)
    ThreadRef job(@PathVariable String merchantId, @PathVariable String jobId) {
        return new ThreadRef(threads.forJob(merchantId, jobId));
    }
}
