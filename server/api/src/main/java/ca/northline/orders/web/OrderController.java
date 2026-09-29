package ca.northline.orders.web;

import static ca.northline.shared.security.MerchantPermission.OPERATE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.orders.application.OrderUseCases.ListOrders;
import ca.northline.orders.application.OrderUseCases.PackOrder;
import ca.northline.orders.application.OrderUseCases.ViewOrder;
import ca.northline.orders.web.OrderResponses.BoardResponse;
import ca.northline.orders.web.OrderResponses.OrderResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Orders · products: the packing list, order detail and "Mark packed". */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/orders")
@RequiredArgsConstructor
class OrderController {

    private final ListOrders listOrders;
    private final ViewOrder viewOrder;
    private final PackOrder packOrder;
    private final OrderWebMapper mapper;

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
}
