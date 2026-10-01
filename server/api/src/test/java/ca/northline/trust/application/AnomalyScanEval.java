package ca.northline.trust.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.northline.ai.AiTestKit;
import ca.northline.ai.application.PromptLibraryAccess;
import ca.northline.ai.eval.EvalReport;
import ca.northline.ai.eval.EvalSuite;
import ca.northline.ai.eval.LabelledSet;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.trust.application.AiScreeningStore.BusinessWeek;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S-133 eval ({@code ai-eval/anomaly-scan.json}): per market week, the rules must pick exactly the businesses labelled
 * "flag" with the expected signals (precision/recall of "flag"), and each flag's explanation — the model's — must cite
 * the numbers and accuse no one.
 */
public final class AnomalyScanEval implements EvalSuite {

    static final LocalDate WEEK = LocalDate.of(2027, 4, 19);

    private final LabelledSet set = LabelledSet.load("anomaly-scan");

    @Override
    public String name() {
        return "anomaly-scan";
    }

    @Override
    public LabelledSet set() {
        return set;
    }

    /** A flag store that keeps what was raised. */
    static final class RecordingFlags {
        final Map<String, Map<String, String>> raised = new HashMap<>();

        TrustFlagStore store() {
            var store = mock(TrustFlagStore.class);
            when(store.raiseUnlessOpen(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> {
                        raised.put(inv.getArgument(2), inv.getArgument(6));
                        return true;
                    });
            return store;
        }
    }

    @Override
    public EvalReport run(AiTestKit kit, String mode) {
        var places = mock(MerchantPlaces.class);
        when(places.of(anyString()))
                .thenReturn(new MerchantPlaces.MerchantPlace(
                        null, false, null, "eval-market", ZoneId.of("UTC"), "", "", null));
        var cases = new ArrayList<EvalReport.Case>();
        var model = kit.client.model();
        for (var c : set.cases()) {
            var id = c.path("id").asString();
            var weeks = new ArrayList<BusinessWeek>();
            var i = 0;
            for (var b : c.path("businesses")) {
                weeks.add(new BusinessWeek(
                        id + "-" + i++,
                        b.path("reviews").asInt(),
                        b.path("average").isNumber() ? b.path("average").asDouble() : null,
                        b.path("priorReviews").asInt(),
                        b.path("priorAverage").isNumber()
                                ? b.path("priorAverage").asDouble()
                                : null,
                        b.path("offPlatform").asInt(),
                        b.path("aiFlags").asInt()));
            }
            var store = mock(AiScreeningStore.class);
            when(store.week(any(), any())).thenReturn(List.copyOf(weeks));
            when(store.claimScan(anyString(), anyString(), any())).thenReturn(true);
            var flags = new RecordingFlags();
            var service = new AnomalyScanService(
                    store,
                    flags.store(),
                    places,
                    kit.completions,
                    new PromptLibraryAccess(),
                    Clock.fixed(WEEK.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
            try {
                service.scan(WEEK);
                var n = 0;
                for (var b : c.path("businesses")) {
                    var merchant = id + "-" + n;
                    var expected = b.path("expected").asString();
                    var evidence = flags.raised.get(merchant);
                    var actual = evidence == null ? "ok" : "flag";
                    var misses = new ArrayList<String>();
                    if (evidence != null) {
                        var wanted = new ArrayList<String>();
                        b.path("signals").forEach(s -> wanted.add(s.asString()));
                        if (!String.join(",", wanted).equals(evidence.get("signals"))) {
                            misses.add("signals " + evidence.get("signals") + " ≠ " + wanted);
                        }
                        if (!"ai".equals(evidence.get("source"))) {
                            misses.add("not explained by the model");
                        }
                        misses.addAll(LabelledSet.contentMisses(c, evidence.getOrDefault("explanation", "")));
                        model = evidence.getOrDefault("model", model);
                    }
                    cases.add(new EvalReport.Case(
                            id + "/" + n,
                            expected,
                            actual,
                            expected.equals(actual) && misses.isEmpty(),
                            evidence == null
                                    ? "no flag"
                                    : misses.isEmpty() ? evidence.get("explanation") : misses.toString(),
                            0,
                            0));
                    n++;
                }
            } catch (RuntimeException e) {
                cases.add(new EvalReport.Case(id, "flag", "error", false, e.toString(), 0, 0));
            }
        }
        return new EvalReport(name(), mode, model, cases);
    }
}
