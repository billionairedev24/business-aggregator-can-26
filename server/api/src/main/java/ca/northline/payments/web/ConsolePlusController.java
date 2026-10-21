package ca.northline.payments.web;

import ca.northline.payments.api.PlusBilling;
import ca.northline.payments.application.MembershipBilling;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — a household's Northline Plus payments and the refund of one (S-162: Northline bills Plus itself, a
 * PaymentIntent per period). Finance reads; refunding needs the {@code refund} action. A refund gives back the period's
 * amount and tax to the card; refunding the current period ends the membership at once. Audited.
 *
 * <pre>
 * GET  /api/v1/console/plus/households/{householdId}/periods    {items: [Period]} newest first
 * POST /api/v1/console/plus/periods/{periodId}/refund           → Refunded; 409 plus_not_refundable
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class ConsolePlusController {

    private final MembershipBilling plus;

    @GetMapping("/api/v1/console/plus/households/{householdId}/periods")
    @RequiresConsole(ConsoleScreen.FINANCE)
    ListResponse<PlusBilling.Period> periods(@PathVariable String householdId) {
        return new ListResponse<>(plus.periodsOf(householdId));
    }

    @PostMapping("/api/v1/console/plus/periods/{periodId}/refund")
    @RequiresConsole(value = ConsoleScreen.FINANCE, actions = ConsoleAction.REFUND)
    MembershipBilling.Refunded refund(@PathVariable String periodId, CurrentStaff staff) {
        return plus.refund(periodId, staff.userId(), staff.roleCodes());
    }
}
