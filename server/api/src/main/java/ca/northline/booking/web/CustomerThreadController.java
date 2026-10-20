package ca.northline.booking.web;

import static ca.northline.shared.security.MerchantPermission.OPERATE;

import ca.northline.booking.application.OpenCustomerThread;
import ca.northline.shared.security.RequiresMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** S-153: "Message the customer" from a quote request or a job card; the answer is the thread to open in Messages. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class CustomerThreadController {

    private final OpenCustomerThread threads;

    record ThreadRef(String threadId) {}

    @PostMapping("/quote-requests/{requestId}/thread")
    @RequiresMerchant(OPERATE)
    ThreadRef quoteRequest(@PathVariable String merchantId, @PathVariable String requestId) {
        return new ThreadRef(threads.forQuoteRequest(merchantId, requestId));
    }

    @PostMapping("/jobs/{jobId}/thread")
    @RequiresMerchant(OPERATE)
    ThreadRef job(@PathVariable String merchantId, @PathVariable String jobId) {
        return new ThreadRef(threads.forJob(merchantId, jobId));
    }
}
