package ca.northline.console.web;

import ca.northline.console.application.MonitorOrders;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.RequiresConsole;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The orders monitor (S-81, design 03 {@code orders}; admin, dispatch, support). An order's delivery is
 * {@code GET /api/v1/console/fulfilment/orders/{orderId}}.
 *
 * <pre>
 * GET /api/v1/console/orders?view=attention|live|escrow|late|all&amp;q=&amp;province=&amp;market=   MonitorOrders.Monitor
 *     422 view: not one of the five · province|market: as on the overview
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/orders")
@RequiredArgsConstructor
class OrdersMonitorController {

    static final String VIEW = "Choose needs attention, live, escrow, late or all.";

    private final MonitorOrders monitor;

    @GetMapping
    @RequiresConsole(ConsoleScreen.ORDERS)
    ResponseEntity<MonitorOrders.Monitor> monitor(
            @RequestParam(defaultValue = "attention") String view,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        var v = Arrays.stream(MonitorOrders.View.values())
                .filter(e -> e.code().equals(view))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("view", "format", VIEW));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(monitor.monitor(new MonitorOrders.Query(v, q, province, market)));
    }
}
