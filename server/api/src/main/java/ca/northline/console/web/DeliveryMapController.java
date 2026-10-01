package ca.northline.console.web;

import ca.northline.console.application.ViewDeliveryMap;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.RequiresConsole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delivery ops map's geometry (S-81, design 03 {@code delivery}; admin, dispatch).
 *
 * <pre>
 * GET /api/v1/console/delivery/map?market=&lt;region market id&gt;   ViewDeliveryMap.DeliveryMap   422 market: unknown
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/delivery/map")
@RequiredArgsConstructor
class DeliveryMapController {

    private final ViewDeliveryMap view;

    @GetMapping
    @RequiresConsole(ConsoleScreen.DELIVERY)
    ViewDeliveryMap.DeliveryMap map(@RequestParam String market) {
        return view.map(market);
    }
}
