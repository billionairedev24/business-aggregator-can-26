package ca.northline.trust.application;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-133 use case: the weekly anomaly scan per market. Deterministic signals ({@link
 * ca.northline.trust.domain.AnomalyRules}) pick the businesses whose week stands out; the model explains each to staff
 * (no names, only the numbers); each becomes an open {@code anomaly_<week>} flag in the console queue. Staff decide.
 */
public interface ScanAnomalies {

    /** Scans the week (Monday to Sunday, UTC) that starts on or before {@code weekStart}'s Monday. Idempotent per market. */
    List<MarketScan> scan(LocalDate weekStart);

    /** Scans the last complete week. */
    List<MarketScan> scanLastWeek();

    /**
     * @param market the region market id, else the province code, else {@code unplaced}
     * @param aiExplained false when the model was unavailable and the rules' own explanation was used
     */
    record MarketScan(
            String market,
            LocalDate weekStart,
            int businesses,
            int flagsRaised,
            @Nullable String summary,
            boolean aiExplained) {}
}
