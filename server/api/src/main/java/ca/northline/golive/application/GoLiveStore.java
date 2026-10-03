package ca.northline.golive.application;

import ca.northline.golive.domain.GateStatus;
import ca.northline.golive.domain.LaunchRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: schema {@code golive}. */
public interface GoLiveStore {

    /** The newest record of each gate of the market, by gate code. */
    Map<String, GateRecord> latestRecords(String marketId);

    void insertRecord(GateRecord record);

    /** The market's pending request (lapsed or not), locked. */
    Optional<LaunchRequest> lockOpenRequest(String marketId);

    Optional<LaunchRequest> lockRequest(String requestId);

    /** Pending requests by market. */
    Map<String, LaunchRequest> openRequests();

    void insertRequest(LaunchRequest request);

    void updateRequest(LaunchRequest request);

    /** Newest first. */
    List<LaunchRequest> requests(String marketId, int limit);

    void insertEvent(MarketEvent event);

    /** Newest first. */
    List<MarketEvent> events(String marketId, int limit);

    /** By day. */
    List<HypercareDay> hypercare(String marketId);

    void insertHypercare(List<HypercareDay> days);

    /** @param source {@code console} or {@code script} */
    record GateRecord(
            String id,
            String marketId,
            String gate,
            GateStatus status,
            String evidence,
            @Nullable String evidenceUrl,
            String source,
            String recordedBy,
            Instant recordedAt) {}

    /** @param kind {@code launched} or {@code rolled_back} */
    record MarketEvent(
            String id,
            String marketId,
            String kind,
            @Nullable String requestId,
            String actorId,
            @Nullable String reason,
            Instant occurredAt) {}

    record HypercareDay(
            String marketId,
            LocalDate day,
            String primary,
            String secondary,
            String business,
            @Nullable String primaryShiftId,
            @Nullable String secondaryShiftId,
            String createdBy,
            Instant createdAt) {}
}
