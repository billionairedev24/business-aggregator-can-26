package ca.northline.golive.application;

import ca.northline.golive.api.PilotReadiness;
import ca.northline.golive.application.GoLiveStore.GateRecord;
import ca.northline.golive.domain.Coverage;
import ca.northline.golive.domain.Gate;
import ca.northline.golive.domain.GateStatus;
import ca.northline.identity.api.OncallRota;
import ca.northline.payments.api.StripeMode;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.RegionEditor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Evaluates each gate of a market (docs/runbooks/go-live.md § Gates). Automatic gates read their source on every call;
 * a source that fails makes that gate {@code pending} ({@code source_error}), never the checklist. Manual gates take
 * the newest record younger than {@code GO_LIVE_RECORD_MAX_AGE}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GateEvaluator {

    private final RegionEditor editor;
    private final PilotReadiness pilots;
    private final OncallRota rota;
    private final StripeMode stripe;
    private final AlertSignals alerts;
    private final UatVerdict uat;
    private final GoLiveProperties props;
    private final Clock clock;

    /** The outcome of one gate before the people are named. */
    record Result(
            Gate gate,
            boolean required,
            GateStatus status,
            String code,
            Map<String, String> params,
            @Nullable GateRecord record,
            boolean recordable) {}

    /** A record counts for the gate: a manual one, or one read from metrics while no backend is configured. */
    boolean recordable(Gate gate) {
        return switch (gate.kind()) {
            case MANUAL -> true;
            case AUTO -> false;
            case AUTO_OR_MANUAL -> !alerts.read().configured();
        };
    }

    List<Result> evaluate(MarketProfile market, Map<String, GateRecord> records) {
        var now = clock.instant();
        var signals = alerts.read();
        var frenchFirst = market.language().frenchFirst();
        var out = new ArrayList<Result>();
        for (var gate : Gate.values()) {
            var required = gate == Gate.FRENCH_COVERAGE ? frenchFirst : gate.required();
            var record = records.get(gate.code());
            Result result;
            try {
                result = switch (gate.kind()) {
                    case AUTO -> automatic(gate, required, market, now);
                    case MANUAL ->
                        gate == Gate.FRENCH_COVERAGE && !frenchFirst
                                ? new Result(
                                        gate,
                                        false,
                                        GateStatus.NOT_APPLICABLE,
                                        "not_french_first",
                                        Map.of(),
                                        null,
                                        false)
                                : manual(gate, required, record, now);
                    case AUTO_OR_MANUAL ->
                        signals.configured()
                                ? fromSignals(gate, required, signals)
                                : manual(gate, required, record, now);
                };
            } catch (RuntimeException e) {
                log.warn("go-live gate {} of {} couldn't be evaluated: {}", gate.code(), market.id(), e.toString());
                result = new Result(gate, required, GateStatus.PENDING, "source_error", Map.of(), null, false);
            }
            out.add(result);
        }
        return List.copyOf(out);
    }

    private Result automatic(Gate gate, boolean required, MarketProfile market, Instant now) {
        return switch (gate) {
            case MARKET_ZONES -> {
                var province = editor.province(market.province()).orElseThrow();
                var zones = editor.zones(province.id()).stream()
                        .filter(z -> z.marketId().equals(market.id()) && z.areaKm2() != null)
                        .count();
                yield auto(
                        gate, required, zones > 0, zones > 0 ? "zones" : "no_zone", Map.of("n", String.valueOf(zones)));
            }
            case PROVINCE_LIVE -> {
                var stage = editor.province(market.province())
                        .map(p -> p.stage().code())
                        .orElse("off");
                yield auto(gate, required, "live".equals(stage), "province_stage", Map.of("stage", stage));
            }
            case UAT_GO_NO_GO -> {
                var verdict = uat.verdict();
                var params = new LinkedHashMap<String, String>();
                params.put("blocking", String.valueOf(verdict.blocking()));
                params.put("untriaged", String.valueOf(verdict.untriaged()));
                params.put("reasons", String.join(",", verdict.reasons()));
                yield auto(gate, required, verdict.go(), verdict.go() ? "uat_go" : "uat_no_go", params);
            }
            case PILOT_BUSINESSES -> {
                var counts = pilots.counts(market.id());
                yield auto(
                        gate,
                        required,
                        counts.ready() >= props.minPilotBusinesses(),
                        "pilot_ready",
                        Map.of(
                                "ready", String.valueOf(counts.ready()),
                                "total", String.valueOf(counts.total()),
                                "blocked", String.valueOf(counts.blocked()),
                                "min", String.valueOf(props.minPilotBusinesses())));
            }
            case ONCALL_COVERAGE -> {
                var until = now.plus(props.oncallHorizon());
                var gap = Coverage.firstGap(
                        rota.between(now, until).stream()
                                .map(s -> new Coverage.Span(s.startsAt(), s.endsAt()))
                                .toList(),
                        now,
                        until);
                yield gap.isPresent()
                        ? auto(
                                gate,
                                required,
                                false,
                                "oncall_gap",
                                Map.of("at", gap.get().toString()))
                        : auto(gate, required, true, "oncall_covered", Map.of("until", until.toString()));
            }
            case STRIPE_LIVE -> {
                var mode = stripe.mode();
                yield auto(
                        gate,
                        required,
                        mode.live(),
                        mode.live() ? "stripe_live" : "stripe_not_live",
                        Map.of(
                                "secretKey", mode.secretKey(),
                                "publishableKey", mode.publishableKey(),
                                "webhookSecret", String.valueOf(mode.webhookSecret()),
                                "connectWebhookSecret", String.valueOf(mode.connectWebhookSecret())));
            }
            default -> throw new IllegalStateException("not an automatic gate: " + gate);
        };
    }

    private static Result fromSignals(Gate gate, boolean required, AlertSignals.Reading signals) {
        if (gate == Gate.ALERT_RULES) {
            var n = signals.alertingRules();
            return n == null
                    ? new Result(gate, required, GateStatus.PENDING, "source_error", Map.of(), null, false)
                    : auto(gate, required, n > 0, n > 0 ? "rules_loaded" : "no_rules", Map.of("n", String.valueOf(n)));
        }
        var firing = signals.firing();
        return firing == null
                ? new Result(gate, required, GateStatus.PENDING, "source_error", Map.of(), null, false)
                : auto(
                        gate,
                        required,
                        firing == 0,
                        firing == 0 ? "none_firing" : "firing",
                        Map.of("n", String.valueOf(firing), "names", String.join(", ", signals.firingNames())));
    }

    private Result manual(Gate gate, boolean required, @Nullable GateRecord record, Instant now) {
        if (record == null) {
            return new Result(gate, required, GateStatus.PENDING, "not_recorded", Map.of(), null, true);
        }
        if (record.recordedAt().plus(props.recordMaxAge()).isBefore(now)) {
            return new Result(
                    gate,
                    required,
                    GateStatus.PENDING,
                    "record_expired",
                    Map.of("at", record.recordedAt().toString()),
                    record,
                    true);
        }
        return new Result(gate, required, record.status(), "recorded", Map.of(), record, true);
    }

    private static Result auto(Gate gate, boolean required, boolean pass, String code, Map<String, String> params) {
        return new Result(gate, required, pass ? GateStatus.PASS : GateStatus.FAIL, code, params, null, false);
    }
}
