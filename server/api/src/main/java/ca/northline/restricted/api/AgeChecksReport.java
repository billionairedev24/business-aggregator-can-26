package ca.northline.restricted.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Trust &amp; safety's report on age-restricted sales (console › Listing vetting › Age checks). Counts only. */
public interface AgeChecksReport {

    /** @param province only checks under this province's rules; null = every province */
    Report report(Instant from, Instant to, @Nullable String province);

    /**
     * @param verifications customers' age checks finished in the period, by state ({@code verified}, {@code failed})
     * @param failures failed age checks by the provider's code
     * @param handoffs ID checks at handoff by outcome ({@code passed}, {@code refused})
     * @param refusals refused handoffs by reason
     * @param byPlace ID checks by place ({@code door}, {@code counter})
     */
    record Report(
            Instant from,
            Instant to,
            @Nullable String province,
            Map<String, Long> verifications,
            Map<String, Long> failures,
            Map<String, Long> handoffs,
            Map<String, Long> refusals,
            Map<String, Long> byPlace,
            List<Refusal> recent) {

        public Report {
            verifications = Map.copyOf(verifications);
            failures = Map.copyOf(failures);
            handoffs = Map.copyOf(handoffs);
            refusals = Map.copyOf(refusals);
            byPlace = Map.copyOf(byPlace);
            recent = List.copyOf(recent);
        }
    }

    /** A refused handoff (ids and codes only). */
    record Refusal(
            String checkId,
            String orderId,
            String orderType,
            @Nullable String province,
            int requiredAge,
            String actorRole,
            String place,
            String reason,
            Instant at) {}
}
