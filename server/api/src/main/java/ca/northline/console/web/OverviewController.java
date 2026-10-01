package ca.northline.console.web;

import ca.northline.console.application.ViewOverview;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.RequiresConsole;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console overview (S-91, design 03 {@code overview}; every console role opens it).
 *
 * <pre>
 * GET /api/v1/console/overview[?province=AB][&amp;market=&lt;region market id&gt;]   ViewOverview.Overview
 *     422 province|market: unknown, or the market outside the province
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/overview")
@RequiredArgsConstructor
class OverviewController {

    private final ViewOverview overview;

    @GetMapping
    @RequiresConsole(ConsoleScreen.OVERVIEW)
    ResponseEntity<ViewOverview.Overview> view(
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(overview.view(new ViewOverview.Query(province, market)));
    }
}
