package ca.northline.booking;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "booking", name = "quotes")
record Quote(
        @Id String id,
        String merchantId,
        String customerId,
        String status,
        int version,
        long totalCents,
        long depositCents,
        Instant validUntil) {
    Quote accept(String by) {
        if (!by.equals(customerId)) throw new IllegalStateException("Only the customer can accept this quote.");
        if (!"sent".equals(status)) throw new IllegalStateException("This quote can no longer be accepted.");
        if (Instant.now().isAfter(validUntil))
            throw new IllegalStateException("This quote has expired. Ask for an updated quote.");
        return new Quote(id, merchantId, customerId, "accepted", version, totalCents, depositCents, validUntil);
    }
}
