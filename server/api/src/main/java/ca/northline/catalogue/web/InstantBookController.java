package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.api.InstantBook;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-156: whether the business's services book instantly, and Northline's pause with its reason (Studio › Availability
 * shows it next to "How bookings come in", whose "Instant book" choice is the business's own switch — saved with the
 * booking rules).
 *
 * <pre>
 * GET /api/v1/merchants/{merchantId}/instant-book   {enabled, effective, paused, pausedAt, pausedCause, pausedReason} (VIEW)
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class InstantBookController {

    private final InstantBook instantBook;

    record InstantBookResponse(
            boolean enabled,
            boolean effective,
            boolean paused,
            @Nullable Instant pausedAt,
            @Nullable String pausedCause,
            @Nullable String pausedReason) {

        static InstantBookResponse of(InstantBook.State s) {
            return new InstantBookResponse(
                    s.enabled(), s.effective(), s.pausedAt() != null, s.pausedAt(), s.pausedCause(), s.pausedReason());
        }
    }

    @GetMapping("/api/v1/merchants/{merchantId}/instant-book")
    @ca.northline.shared.security.RequiresMerchant(VIEW)
    InstantBookResponse get(@PathVariable String merchantId) {
        return InstantBookResponse.of(instantBook.state(merchantId));
    }
}
