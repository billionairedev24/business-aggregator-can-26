package ca.northline.trust.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S-133 AI trust &amp; safety assist ({@code northline.trust.ai.*}).
 *
 * @param screening whether the screening job runs ({@code TRUST_AI_SCREENING_ENABLED}); off, nothing is sent to the model
 *     and the deterministic detectors keep raising their flags
 * @param screenMessages whether messages are screened too (the busiest source; listings and reviews always are)
 * @param interval how often the screening job runs
 * @param batch items per source per run (the rest waits for the next run)
 * @param initialLookback how far back the first run starts, per source
 * @param anomalyScan whether the weekly anomaly scan runs ({@code TRUST_AI_ANOMALY_SCAN_ENABLED})
 * @param anomalyCron when it runs (UTC; it scans the week, Monday to Sunday UTC, that ended before)
 */
@ConfigurationProperties("northline.trust.ai")
public record TrustAiProperties(
        @DefaultValue("true") boolean screening,
        @DefaultValue("true") boolean screenMessages,
        @DefaultValue("PT15M") Duration interval,
        @DefaultValue("25") int batch,
        @DefaultValue("P1D") Duration initialLookback,
        @DefaultValue("true") boolean anomalyScan,
        @DefaultValue("0 10 12 * * MON") String anomalyCron) {}
