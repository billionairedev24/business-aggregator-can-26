package ca.northline.orders.web;

import ca.northline.orders.application.AfterSaleUseCases.CancelOrders;
import ca.northline.orders.application.AfterSaleUseCases.FailedDeliveries;
import ca.northline.orders.application.AfterSaleUseCases.ReturnRequest;
import ca.northline.orders.application.AfterSaleUseCases.ReturnView;
import ca.northline.orders.application.AfterSaleUseCases.Returns;
import ca.northline.orders.application.TrackOrder;
import ca.northline.orders.application.TrackOrder.OrderTracking;
import ca.northline.orders.domain.AfterSale;
import ca.northline.shared.Bytes;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The customer's side of S-151 (single-factor sessions allowed, like tracking; someone else's order or return is 404):
 *
 * <pre>
 * POST /api/v1/me/orders/{orderId}/cancel            {reason, note?} → OrderTracking; 409 too_late / order_closed /
 *                                                    not_cancellable (configuration: before any shop packed)
 * POST /api/v1/me/orders/{orderId}/delivery-failure  {choice: redeliver|refund} → OrderTracking; 409 not_failed /
 *                                                    already_chosen / no_run
 * GET  /api/v1/me/orders/{orderId}/returns           {items: [ReturnView]}
 * POST /api/v1/me/orders/{orderId}/returns           multipart lineIds (repeated), reason, note?, photo → 201 ReturnView;
 *                                                    409 not_delivered / window_closed / not_returnable / already_returning
 * POST /api/v1/me/returns/{returnId}/withdraw        → ReturnView (before the shop has it)
 * POST /api/v1/me/returns/{returnId}/escalate        {statement} → ReturnView: a declined return to a Northline agent
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class ConsumerAfterSaleController {

    private final CancelOrders cancel;
    private final FailedDeliveries failures;
    private final Returns returns;
    private final TrackOrder track;

    record CancelRequest(
            @NotBlank(message = AfterSale.REASON)
            @Pattern(
                    regexp = "changed_mind|ordered_by_mistake|delivery_too_late|found_elsewhere|other",
                    message = AfterSale.REASON)
            String reason,

            @Nullable @Size(max = AfterSale.MAX_NOTE, message = AfterSale.NOTE_LENGTH)
            String note) {}

    record ChoiceRequest(
            @NotBlank(message = AfterSale.CHOICE) @Pattern(regexp = "redeliver|refund", message = AfterSale.CHOICE)
            String choice) {}

    record EscalateRequest(
            @NotBlank(message = AfterSale.ESCALATE_STATEMENT)
            @Size(max = AfterSale.MAX_RETURN_NOTE, message = AfterSale.RETURN_NOTE_LENGTH)
            String statement) {}

    @PostMapping("/api/v1/me/orders/{orderId}/cancel")
    ResponseEntity<OrderTracking> cancel(
            CurrentUser user, @PathVariable String orderId, @Valid @RequestBody CancelRequest body) {
        cancel.byCustomer(user.userId(), orderId, body.reason(), body.note());
        return noStore(track.view(user.userId(), orderId));
    }

    @PostMapping("/api/v1/me/orders/{orderId}/delivery-failure")
    ResponseEntity<OrderTracking> choose(
            CurrentUser user, @PathVariable String orderId, @Valid @RequestBody ChoiceRequest body) {
        return noStore(failures.choose(user.userId(), orderId, body.choice()));
    }

    @GetMapping("/api/v1/me/orders/{orderId}/returns")
    ResponseEntity<ListResponse<ReturnView>> returns(CurrentUser user, @PathVariable String orderId) {
        return noStore(new ListResponse<>(returns.ofCustomer(user.userId(), orderId)));
    }

    @PostMapping(path = "/api/v1/me/orders/{orderId}/returns", consumes = "multipart/form-data")
    ResponseEntity<ReturnView> request(
            CurrentUser user,
            @PathVariable String orderId,
            @RequestParam(name = "lineIds", required = false) @Nullable List<String> lineIds,
            @RequestParam(name = "reason", required = false) @Nullable String reason,
            @RequestParam(name = "note", required = false) @Nullable String note,
            @RequestPart(name = "photo", required = false) @Nullable MultipartFile photo)
            throws IOException {
        var view = returns.request(
                user.userId(),
                orderId,
                new ReturnRequest(
                        lineIds == null ? List.of() : lineIds,
                        reason == null ? "" : reason,
                        note,
                        photo == null || photo.isEmpty() ? null : Bytes.of(photo.getBytes())));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(view);
    }

    @PostMapping("/api/v1/me/returns/{returnId}/withdraw")
    ResponseEntity<ReturnView> withdraw(CurrentUser user, @PathVariable String returnId) {
        return noStore(returns.withdraw(user.userId(), returnId));
    }

    @PostMapping("/api/v1/me/returns/{returnId}/escalate")
    ResponseEntity<ReturnView> escalate(
            CurrentUser user, @PathVariable String returnId, @Valid @RequestBody EscalateRequest body) {
        return noStore(returns.escalate(user.userId(), returnId, body.statement()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
