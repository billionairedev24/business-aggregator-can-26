package ca.northline.console.web;

import ca.northline.console.application.ViewFinance;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.RequiresConsole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The finance screen's figures (S-85, design 03 {@code finance}; admin, finance). Reconciliation and exports are
 * {@code /api/v1/console/payments/…}.
 *
 * <pre>
 * GET /api/v1/console/finance   ViewFinance.Finance
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/finance")
@RequiredArgsConstructor
class FinanceController {

    private final ViewFinance finance;

    @GetMapping
    @RequiresConsole(ConsoleScreen.FINANCE)
    ResponseEntity<ViewFinance.Finance> finance() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(finance.finance());
    }
}
