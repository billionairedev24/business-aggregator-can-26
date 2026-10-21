package ca.northline.trust.web;

import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.RequiresConsole;
import ca.northline.trust.application.TrustFlagQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — Trust &amp; safety › Weekly scan (S-158): S-133's anomaly scan per market and week (UTC, Monday
 * start), its market summary, and the businesses it flagged with their signals and explanation. Read-only: the flags
 * are decided in the queue.
 */
@RestController
@RequiredArgsConstructor
class AnomalyScanController {

    private final TrustFlagQueue queue;

    @GetMapping("/api/v1/console/trust/anomaly-scans")
    @RequiresConsole(ConsoleScreen.TRUST)
    ListResponse<TrustFlagQueue.ScanView> scans(@RequestParam(defaultValue = "20") int limit) {
        return new ListResponse<>(queue.scans(limit));
    }
}
