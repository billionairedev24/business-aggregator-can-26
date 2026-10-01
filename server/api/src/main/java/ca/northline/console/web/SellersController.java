package ca.northline.console.web;

import ca.northline.console.application.ViewSellers;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.RequiresConsole;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sellers directory and seller detail (S-82, design 03 {@code sellers}; admin, trust &amp; safety, support). The
 * oversight actions are {@code /api/v1/console/merchants/{businessId}/…} (merchants module).
 *
 * <pre>
 * GET /api/v1/console/sellers?q=&amp;province=&amp;market=   ViewSellers.Directory   422 province|market
 * GET /api/v1/console/sellers/{sellerId}                ViewSellers.Detail      404
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/sellers")
@RequiredArgsConstructor
@RequiresConsole(ConsoleScreen.SELLERS)
class SellersController {

    private final ViewSellers sellers;

    @GetMapping
    ResponseEntity<ViewSellers.Directory> directory(
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sellers.directory(new ViewSellers.Query(q, province, market)));
    }

    @GetMapping("/{sellerId}")
    ResponseEntity<ViewSellers.Detail> detail(@PathVariable String sellerId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sellers.detail(sellerId));
    }
}
