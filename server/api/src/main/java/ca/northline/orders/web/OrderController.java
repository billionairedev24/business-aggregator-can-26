package ca.northline.orders.web;

import static ca.northline.shared.security.MerchantPermission.OPERATE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.orders.application.AfterSaleUseCases.CancelOrders;
import ca.northline.orders.application.OrderUseCases.ListOrders;
import ca.northline.orders.application.OrderUseCases.PackOrder;
import ca.northline.orders.application.OrderUseCases.ViewOrder;
import ca.northline.orders.domain.AfterSale;
import ca.northline.orders.web.OrderResponses.BoardResponse;
import ca.northline.orders.web.OrderResponses.OrderResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orders · products: the packing list, order detail and "Mark packed"; S-151: {@code POST …/{orderId}/cancel
 * {reason, note?, restock}} cancels this shop's lines (the whole order when nothing else is on it) until the courier
 * picks them up — 409 {@code too_late} / {@code nothing_to_cancel} / {@code order_closed}.
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/orders")
@RequiredArgsConstructor
class OrderController {

    private final ListOrders listOrders;
    private final ViewOrder viewOrder;
    private final PackOrder packOrder;
    private final CancelOrders cancelOrders;
    private final OrderWebMapper mapper;

    record CancelRequest(
            @NotBlank(message = AfterSale.REASON)
            @Pattern(
                    regexp = "out_of_stock|cannot_fulfil|customer_request|suspected_fraud|other",
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

    @GetMapping
    @RequiresMerchant(VIEW)
    BoardResponse orders(@PathVariable String merchantId) {
        return mapper.toResponse(listOrders.board(merchantId));
    }

    @GetMapping("/{orderId}")
    @RequiresMerchant(VIEW)
    OrderResponse order(@PathVariable String merchantId, @PathVariable String orderId) {
        return mapper.toResponse(viewOrder.view(merchantId, orderId));
    }

    /** "Mark packed" → Awaiting pickup. 409 {@code order_state} when there is nothing left to pack. */
    @PostMapping("/{orderId}/pack")
    @RequiresMerchant(OPERATE)
    OrderResponse pack(@PathVariable String merchantId, @PathVariable String orderId, CurrentMember member) {
        return mapper.toResponse(packOrder.pack(merchantId, orderId, member.userId()));
    }

    @PostMapping("/{orderId}/cancel")
    @RequiresMerchant(OPERATE)
    OrderResponse cancel(
            @PathVariable String merchantId,
            @PathVariable String orderId,
            @Valid @RequestBody CancelRequest body,
            CurrentMember member) {
        cancelOrders.bySeller(merchantId, orderId, member.userId(), body.reason(), body.note(), body.restocked());
        return mapper.toResponse(viewOrder.view(merchantId, orderId));
    }
}
