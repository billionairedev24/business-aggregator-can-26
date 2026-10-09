package ca.northline.payments.web;

import ca.northline.payments.api.CourierTips;
import ca.northline.payments.api.CourierTips.Tip;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — courier tips of an order and their refund (mobile gaps part 2). A tip goes back to the card only
 * when the delivery didn't happen, it was charged twice or for the wrong amount; finance decides ({@code refund}).
 *
 * <pre>
 * GET  /api/v1/console/orders/{orderId}/tips      {items: [Tip]}
 * POST /api/v1/console/tips/{id}/refund            {reason: not_delivered | duplicate | amount_error} → Tip
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class ConsoleTipController {

    private final CourierTips tips;

    record RefundRequest(
            @NotBlank(message = CourierTips.REASON)
            @Pattern(regexp = "not_delivered|duplicate|amount_error", message = CourierTips.REASON)
            String reason) {}

    @GetMapping("/api/v1/console/orders/{orderId}/tips")
    @RequiresConsole(ConsoleScreen.FINANCE)
    ListResponse<Tip> list(@PathVariable String orderId) {
        return new ListResponse<>(tips.ofOrder(orderId));
    }

    @PostMapping("/api/v1/console/tips/{id}/refund")
    @RequiresConsole(value = ConsoleScreen.FINANCE, actions = ConsoleAction.REFUND)
    Tip refund(@PathVariable String id, @Valid @RequestBody RefundRequest body, CurrentStaff staff) {
        return tips.refund(id, body.reason(), staff.userId(), staff.roleCodes());
    }
}
