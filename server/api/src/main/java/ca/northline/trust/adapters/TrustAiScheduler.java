package ca.northline.trust.adapters;

import ca.northline.trust.application.ScanAnomalies;
import ca.northline.trust.application.ScreenTrustContent;
import ca.northline.trust.application.TrustAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-133: runs the AI screening every {@code northline.trust.ai.interval} and the anomaly scan weekly
 * ({@code northline.trust.ai.anomaly-cron}, UTC). Safe on every replica: the screening takes a per-source database
 * lock and each market's scan is claimed once per week. Not under {@code test} (tests call the use cases).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class TrustAiScheduler {

    private final ScreenTrustContent screening;
    private final ScanAnomalies anomalies;
    private final TrustAiProperties properties;

    @Scheduled(fixedDelayString = "${northline.trust.ai.interval:PT15M}", initialDelayString = "PT2M")
    void screen() {
        if (!properties.screening()) {
            return;
        }
        try {
            var result = screening.screenNew();
            if (result.screened() > 0 || !result.deferred().isEmpty()) {
                log.info(
                        "Trust screening: {} item(s) screened, {} flag(s) raised, paused: {}",
                        result.screened(),
                        result.flagged(),
                        result.deferred());
            }
        } catch (RuntimeException e) {
            log.error("Trust screening failed; retrying next run", e);
        }
    }

    @Scheduled(cron = "${northline.trust.ai.anomaly-cron:0 10 12 * * MON}", zone = "UTC")
    void scanAnomalies() {
        if (!properties.anomalyScan()) {
            return;
        }
        try {
            for (var scan : anomalies.scanLastWeek()) {
                log.info(
                        "Anomaly scan {} week {}: {} business(es), {} flag(s), explained by AI: {}",
                        scan.market(),
                        scan.weekStart(),
                        scan.businesses(),
                        scan.flagsRaised(),
                        scan.aiExplained());
            }
        } catch (RuntimeException e) {
            log.error("Anomaly scan failed; it runs again next week (or call the use case)", e);
        }
    }
}
