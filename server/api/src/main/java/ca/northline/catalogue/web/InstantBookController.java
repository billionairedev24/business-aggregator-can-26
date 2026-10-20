package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.api.InstantBook;
import ca.northline.catalogue.application.SwitchInstantBook;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-156: the business's instant-book switch (Studio › Availability). Each service keeps its own setting; this one turns
 * instant booking on or off for all of them, and shows Northline's pause with its reason.
 *
 * <pre>
 * GET /api/v1/merchants/{merchantId}/instant-book            {enabled, effective, paused, pausedAt, pausedCause, pausedReason} (VIEW)
 * PUT /api/v1/merchants/{merchantId}/instant-book {enabled}  same (EDIT) · 409 instant_book_paused when turning it on while paused
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class InstantBookController {

    static final String ENABLED_REQUIRED = "Choose on or off.";

    private final InstantBook instantBook;
    private final SwitchInstantBook switcher;

    record SwitchRequest(
            @NotNull(message = ENABLED_REQUIRED) Boolean enabled) {}

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
    @RequiresMerchant(VIEW)
    InstantBookResponse get(@PathVariable String merchantId) {
        return InstantBookResponse.of(instantBook.state(merchantId));
    }

    @PutMapping("/api/v1/merchants/{merchantId}/instant-book")
    @RequiresMerchant(EDIT)
    InstantBookResponse put(
            @PathVariable String merchantId, @Valid @RequestBody SwitchRequest body, CurrentMember member) {
        return InstantBookResponse.of(switcher.set(merchantId, body.enabled(), member.userId()));
    }
}
