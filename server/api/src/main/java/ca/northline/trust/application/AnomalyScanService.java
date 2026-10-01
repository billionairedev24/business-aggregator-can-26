package ca.northline.trust.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import ca.northline.ai.api.Prompts;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.Ids;
import ca.northline.trust.domain.AnomalyRules;
import ca.northline.trust.domain.AnomalyRules.Signal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link ScanAnomalies}: one model call per market with signals (prompt {@code anomaly-scan}, standard model, system
 * budget). The model sees letters and counts only — no business names, ids, places or texts. When it is unavailable
 * the flags still go to staff with the rules' explanation ({@code source: rules}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AnomalyScanService implements ScanAnomalies {

    static final String JOB = "anomaly-scan";
    /** Businesses explained per call; more in one market get the rules' explanation. */
    static final int MAX_PER_CALL = 26;

    private final AiScreeningStore store;
    private final TrustFlagStore flags;
    private final MerchantPlaces places;
    private final AiCompletions ai;
    private final Prompts prompts;
    private final Clock clock;

    private record Flagged(String merchantId, AnomalyRules.Week week, List<Signal> signals) {}

    @Override
    public List<MarketScan> scanLastWeek() {
        var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return scan(
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1));
    }

    @Override
    public List<MarketScan> scan(LocalDate weekStart) {
        var monday = weekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var from = monday.atStartOfDay(ZoneOffset.UTC).toInstant();
        var to = monday.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var byMarket = new TreeMap<String, List<Flagged>>();
        for (var b : store.week(from, to)) {
            var week = new AnomalyRules.Week(
                    b.reviews(),
                    b.averageRating(),
                    b.priorReviews(),
                    b.priorAverageRating(),
                    b.offPlatformFlags(),
                    b.aiFlags());
            var signals = AnomalyRules.signals(week);
            if (!signals.isEmpty()) {
                byMarket.computeIfAbsent(market(b.merchantId()), k -> new ArrayList<>())
                        .add(new Flagged(b.merchantId(), week, signals));
            }
        }
        var out = new ArrayList<MarketScan>();
        byMarket.forEach((market, businesses) -> {
            var scanId = Ids.next();
            if (store.claimScan(scanId, market, monday)) {
                out.add(scanMarket(scanId, market, monday, businesses));
            }
        });
        return out;
    }

    private MarketScan scanMarket(String scanId, String market, LocalDate monday, List<Flagged> businesses) {
        var prompt = prompts.get("anomaly-scan");
        var explanations = new HashMap<String, String>();
        String summary = null;
        String model = null;
        var input = new StringBuilder("Week: ")
                .append(monday)
                .append(" to ")
                .append(monday.plusDays(6))
                .append(" (UTC).\n");
        var refs = new LinkedHashMap<String, Flagged>();
        for (var i = 0; i < Math.min(businesses.size(), MAX_PER_CALL); i++) {
            var ref = String.valueOf((char) ('A' + i));
            var b = businesses.get(i);
            refs.put(ref, b);
            input.append(ref)
                    .append(": signals ")
                    .append(String.join(
                            ", ", b.signals().stream().map(Signal::code).toList()))
                    .append("; ")
                    .append(AnomalyRules.facts(b.week()))
                    .append('\n');
        }
        try {
            var answer = ai.complete(
                    Request.of(AiFeature.ANOMALY_SCAN, Caller.system(JOB), prompt, prompt.text(), input.toString())
                            .asJson()
                            .withMaxTokens(1500));
            model = answer.model();
            var json = answer.json().orElse(null);
            if (json != null) {
                for (var item : json.path("businesses")) {
                    var ref = item.path("ref").asString("").strip();
                    var text = item.path("explanation").asString("").strip();
                    if (refs.containsKey(ref) && !text.isEmpty()) {
                        explanations.put(refs.get(ref).merchantId(), clip(text, 400));
                    }
                }
                var s = json.path("summary").asString("").strip();
                summary = s.isEmpty() ? null : clip(s, 500);
            }
        } catch (AiUnavailable | AiRateLimited e) {
            log.info("Anomaly scan of market {} explained by the rules only: {}", market, e.getMessage());
        }
        var raised = 0;
        for (var b : businesses) {
            var aiText = explanations.get(b.merchantId());
            var evidence = new LinkedHashMap<String, String>();
            evidence.put("source", aiText == null ? "rules" : "ai");
            evidence.put(
                    "signals",
                    String.join(",", b.signals().stream().map(Signal::code).toList()));
            evidence.put("explanation", aiText == null ? AnomalyRules.describe(b.signals(), b.week()) : aiText);
            evidence.put("facts", AnomalyRules.facts(b.week()));
            evidence.put("weekStart", monday.toString());
            evidence.put("market", market);
            evidence.put("scanId", scanId);
            if (aiText != null && model != null) {
                evidence.put("model", model);
                evidence.put("prompt", prompt.id());
            }
            if (flags.raiseUnlessOpen(
                    Ids.next(),
                    "merchant",
                    b.merchantId(),
                    "anomaly_" + monday,
                    b.merchantId(),
                    Caller.SYSTEM + JOB,
                    Map.copyOf(evidence))) {
                raised++;
            }
        }
        var aiExplained = !explanations.isEmpty();
        store.completeScan(
                scanId,
                businesses.size(),
                raised,
                summary,
                aiExplained ? model : null,
                aiExplained ? prompt.id() : null);
        return new MarketScan(market, monday, businesses.size(), raised, summary, aiExplained);
    }

    private String market(String merchantId) {
        var place = places.of(merchantId);
        if (place.marketId() != null) {
            return place.marketId();
        }
        return place.province() != null && place.ownProvince() ? place.province() : "unplaced";
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
