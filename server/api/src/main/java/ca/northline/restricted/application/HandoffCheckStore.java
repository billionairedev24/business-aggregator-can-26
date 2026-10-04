package ca.northline.restricted.application;

import ca.northline.restricted.api.AgeChecksReport.Refusal;
import ca.northline.restricted.api.HandoffChecks.Check;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** {@code restricted.handoff_checks} and the report's counts. */
public interface HandoffCheckStore {

    void insert(String id, Check check);

    Map<String, Long> handoffs(Instant from, Instant to, @Nullable String province);

    Map<String, Long> refusals(Instant from, Instant to, @Nullable String province);

    Map<String, Long> byPlace(Instant from, Instant to, @Nullable String province);

    List<Refusal> recentRefusals(Instant from, Instant to, @Nullable String province, int limit);

    Map<String, Long> verifications(Instant from, Instant to);

    Map<String, Long> failures(Instant from, Instant to);
}
