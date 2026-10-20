package ca.northline.orders.web;

import static ca.northline.shared.security.MerchantPermission.OPERATE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.orders.application.AfterSaleUseCases.ReturnView;
import ca.northline.orders.application.AfterSaleUseCases.Returns;
import ca.northline.orders.domain.AfterSale;
import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio · Orders › Returns (S-151): the shop's returns, the customer's photo, and its decisions.
 *
 * <pre>
 * GET  /api/v1/merchants/{merchantId}/returns                       {items: [ReturnView]}, newest first
 * GET  /api/v1/merchants/{merchantId}/returns/{returnId}            ReturnView
 * GET  /api/v1/merchants/{merchantId}/returns/{returnId}/photo      the customer's photo (no-store, nosniff)
 * POST /api/v1/merchants/{merchantId}/returns/{returnId}/approve    {method: pickup|dropoff, restock}
 * POST /api/v1/merchants/{merchantId}/returns/{returnId}/decline    {reason} — the customer may take it to an agent
 * POST /api/v1/merchants/{merchantId}/returns/{returnId}/received   the shop has it back → refunded at once
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/returns")
@RequiredArgsConstructor
class ReturnController {

    private final Returns returns;

    record ApproveRequest(
            @NotBlank(message = AfterSale.RETURN_METHOD)
            @Pattern(regexp = "pickup|dropoff", message = AfterSale.RETURN_METHOD)
            String method,

            @Nullable Boolean restock) {

        /** Absent: not restocked. */
        boolean restocked() {
            return restock == null ? false : restock;
        }
    }

    record DeclineRequest(
            @NotBlank(message = AfterSale.DECLINE_REASON)
            @Size(max = AfterSale.MAX_NOTE, message = AfterSale.DECLINE_LENGTH)
            String reason) {}

    @GetMapping
    @RequiresMerchant(VIEW)
    ListResponse<ReturnView> list(@PathVariable String merchantId) {
        return new ListResponse<>(returns.ofMerchant(merchantId));
    }

    @GetMapping("/{returnId}")
    @RequiresMerchant(VIEW)
    ReturnView one(@PathVariable String merchantId, @PathVariable String returnId) {
        return returns.ofMerchant(merchantId, returnId);
    }

    @GetMapping("/{returnId}/photo")
    @RequiresMerchant(VIEW)
    ResponseEntity<byte[]> photo(@PathVariable String merchantId, @PathVariable String returnId) {
        var photo = returns.photo(merchantId, returnId).orElseThrow(() -> new NotFound("photo", returnId));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .body(photo.bytes().toArray());
    }

    @PostMapping("/{returnId}/approve")
    @RequiresMerchant(OPERATE)
    ReturnView approve(
            @PathVariable String merchantId,
            @PathVariable String returnId,
            @Valid @RequestBody ApproveRequest body,
            CurrentMember member) {
        return returns.approve(merchantId, returnId, member.userId(), body.method(), body.restocked());
    }

    @PostMapping("/{returnId}/decline")
    @RequiresMerchant(OPERATE)
    ReturnView decline(
            @PathVariable String merchantId,
            @PathVariable String returnId,
            @Valid @RequestBody DeclineRequest body,
            CurrentMember member) {
        return returns.decline(merchantId, returnId, member.userId(), body.reason());
    }

    @PostMapping("/{returnId}/received")
    @RequiresMerchant(OPERATE)
    ReturnView received(@PathVariable String merchantId, @PathVariable String returnId, CurrentMember member) {
        return returns.received(merchantId, returnId, member.userId());
    }
}
