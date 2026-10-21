package ca.northline.trust.application;

import ca.northline.shared.Ids;
import ca.northline.trust.api.NoShowReports;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link NoShowReports}: flags on the booking (target {@code booking}), ids only in the evidence. */
@Service
@RequiredArgsConstructor
@Transactional
class NoShowReportService implements NoShowReports {

    private final TrustFlagStore flags;

    @Override
    public boolean providerNoShow(String merchantId, String bookingId, String customerId) {
        return flags.raiseUnlessOpen(
                Ids.next(),
                "booking",
                bookingId,
                TrustRulesService.NO_SHOW,
                merchantId,
                customerId,
                Map.of("party", "provider", "bookingId", bookingId));
    }

    @Override
    public boolean customerNoShow(String merchantId, String bookingId, String customerId, String reportedBy) {
        return flags.raiseUnlessOpen(
                Ids.next(),
                "booking",
                bookingId,
                TrustRulesService.CUSTOMER_NO_SHOW,
                merchantId,
                reportedBy,
                Map.of("party", "customer", "bookingId", bookingId, "customerId", customerId));
    }
}
