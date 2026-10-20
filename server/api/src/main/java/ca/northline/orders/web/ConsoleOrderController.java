package ca.northline.orders.web;

import ca.northline.orders.application.AfterSaleUseCases.AfterSaleView;
import ca.northline.orders.application.AfterSaleUseCases.CancelOrders;
import ca.northline.orders.application.AfterSaleUseCases.Cancelled;
import ca.northline.orders.application.AfterSaleUseCases.OrderAfterSale;
import ca.northline.orders.domain.AfterSale;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — an order's after-sale (S-151). The orders screen reads it; cancelling also needs the
 * {@code support} action (support, support leads, trust &amp; safety, admins) and is written to the platform audit log
 * ({@code orders.order_cancelled}, codes only — the note stays on the order).
 *
 * <pre>
 * GET  /api/v1/console/orders/{orderId}/after-sale   {orderId, state, cancellations, deliveryFailure, returns}
 * POST /api/v1/console/orders/{orderId}/cancel       {reason, note?, restock} → {cancellationId, state, whole, lineIds};
 *                                                    409 too_late (delivered) / order_closed / not_cancellable
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class ConsoleOrderController {

    private final CancelOrders cancel;
    private final OrderAfterSale afterSale;

    record CancelRequest(
            @NotBlank(message = AfterSale.REASON)
            @Pattern(
                    regexp = "customer_request|merchant_request|fraud|payment_issue|delivery_issue|other",
                    message = AfterSale.REASON)
            String reason,

            @Nullable @Size(max = AfterSale.MAX_NOTE, message = AfterSale.NOTE_LENGTH)
            String note,

            @Nullable Boolean restock) {

        /** Absent: the units go back on sale (the goods never left the shop). */
        boolean restocked() {
            return restock == null ? true : restock;
        }
    }

    @GetMapping("/api/v1/console/orders/{orderId}/after-sale")
    @RequiresConsole(ConsoleScreen.ORDERS)
    AfterSaleView afterSale(@PathVariable String orderId) {
        return afterSale.of(orderId);
    }

    @PostMapping("/api/v1/console/orders/{orderId}/cancel")
    @RequiresConsole(value = ConsoleScreen.ORDERS, actions = ConsoleAction.SUPPORT)
    Cancelled cancel(@PathVariable String orderId, @Valid @RequestBody CancelRequest body, CurrentStaff staff) {
        return cancel.byStaff(orderId, staff.userId(), staff.roleCodes(), body.reason(), body.note(), body.restocked());
    }
}
