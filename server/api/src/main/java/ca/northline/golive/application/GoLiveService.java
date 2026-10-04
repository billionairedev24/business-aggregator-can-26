package ca.northline.golive.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.golive.application.GoLiveStore.GateRecord;
import ca.northline.golive.application.GoLiveStore.HypercareDay;
import ca.northline.golive.application.GoLiveStore.MarketEvent;
import ca.northline.golive.domain.Gate;
import ca.northline.golive.domain.GateStatus;
import ca.northline.golive.domain.HypercareRotation;
import ca.northline.golive.domain.LaunchRequest;
import ca.northline.identity.api.OncallRota;
import ca.northline.identity.api.StaffDirectory;
import ca.northline.merchants.api.PilotCohort;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.RegionEditor;
import ca.northline.region.api.RegionEditor.RegionRef;
import ca.northline.region.api.Regions;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link GoLiveChecklist} and {@link GoLiveSwitch}. The stage is written through {@link RegionEditor} (the region
 * module owns it), with the same {@code region.stage_changed} audit entry the switchboard writes, and the region model
 * is re-read after commit; the market's businesses are shown or hidden through {@link PilotCohort}.
 */
@Service
@Transactional
@RequiredArgsConstructor
@EnableConfigurationProperties(GoLiveProperties.class)
class GoLiveService implements GoLiveChecklist, GoLiveSwitch {

    private static final Set<String> RECORD_STATUSES = Set.of("pass", "fail", "not_applicable");
    private static final String MARKET = "market";

    private final GoLiveStore store;
    private final GateEvaluator gates;
    private final Regions regions;
    private final RegionEditor editor;
    private final PilotCohort pilots;
    private final OncallRota rota;
    private final StaffDirectory staff;
    private final AuditTrail audit;
    private final GoLiveProperties props;
    private final Clock clock;

    // ── reads ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<MarketSummary> markets() {
        var open = store.openRequests();
        var now = clock.instant();
        return regions.markets().stream()
                .map(m -> new MarketSummary(
                        m.id(),
                        m.city(),
                        m.province(),
                        stage(m).code(),
                        Optional.ofNullable(open.get(m.id()))
                                .filter(r -> !r.lapsed(now))
                                .isPresent(),
                        store.events(m.id(), 10).stream()
                                .filter(e -> e.kind().equals("launched"))
                                .map(MarketEvent::occurredAt)
                                .findFirst()
                                .orElse(null)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Checklist checklist(String marketId) {
        return view(market(marketId));
    }

    @Override
    public Checklist record(String marketId, String gateCode, Attestation a, Actor actor) {
        var market = market(marketId);
        var gate = Gate.fromCode(gateCode).orElseThrow(() -> RuleViolation.of("gate", "exists", GATE));
        if (!RECORD_STATUSES.contains(a.status())) {
            throw RuleViolation.of("status", "format", STATUS);
        }
        var evidence = a.evidence().strip();
        if (evidence.isEmpty() || evidence.length() > 1000) {
            throw RuleViolation.of("evidence", "length", EVIDENCE);
        }
        var url = a.evidenceUrl() == null || a.evidenceUrl().isBlank()
                ? null
                : a.evidenceUrl().strip();
        if (url != null && !https(url)) {
            throw RuleViolation.of("evidenceUrl", "format", EVIDENCE_URL);
        }
        if (!gates.recordable(gate)) {
            throw new Conflict("gate_automatic", AUTOMATIC);
        }
        var source = "script".equals(a.source()) ? "script" : "console";
        var now = clock.instant();
        store.insertRecord(new GateRecord(
                Ids.next(),
                market.id(),
                gate.code(),
                CodedEnum.fromCode(GateStatus.class, a.status()),
                evidence,
                url,
                source,
                actor.userId(),
                now));
        audit(
                actor,
                "golive.gate_recorded",
                market.id(),
                null,
                Map.of("gate", gate.code(), "status", a.status(), "source", source));
        return view(market);
    }

    // ── the switch ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Checklist request(String marketId, @Nullable String note, @Nullable String overrideReason, Actor actor) {
        var market = market(marketId);
        var ref = lock(market);
        if (ref.stage() != LaunchStatus.PILOT) {
            throw new Conflict("not_pilot", NOT_PILOT);
        }
        var now = clock.instant();
        var open = store.lockOpenRequest(market.id());
        if (open.isPresent()) {
            if (!open.get().lapsed(now)) {
                throw new Conflict("request_pending", PENDING);
            }
            store.updateRequest(open.get().expire());
        }
        var blocking = blocking(market);
        var request = LaunchRequest.open(
                Ids.next(), market.id(), actor.userId(), now, props.requestTtl(), note, overrideReason, blocking);
        if (!blocking.isEmpty() && !request.override()) {
            throw new Conflict("not_ready", NOT_READY);
        }
        store.insertRequest(request);
        var after = new LinkedHashMap<String, Object>();
        after.put("requestId", request.id());
        after.put("override", request.override());
        after.put("blocking", blocking);
        audit(actor, "golive.launch_requested", market.id(), null, after);
        return view(market);
    }

    @Override
    public Checklist approve(String marketId, String requestId, String confirm, Actor actor) {
        var market = market(marketId);
        confirm(market, confirm);
        var ref = lock(market);
        var request = store.lockRequest(requestId)
                .filter(r -> r.marketId().equals(market.id()))
                .orElseThrow(() -> new NotFound("launch request", requestId));
        var approved = request.approve(actor.userId(), clock.instant());
        if (ref.stage() != LaunchStatus.PILOT) {
            throw new Conflict("not_pilot", NOT_PILOT);
        }
        // the rules no override lifts: never above the province, never without a delivery zone
        var province = editor.province(market.province()).orElseThrow();
        if (province.stage() != LaunchStatus.LIVE) {
            throw RuleViolation.of("stage", "province", ABOVE_PROVINCE);
        }
        if (editor.zones(province.id()).stream()
                .noneMatch(z -> z.marketId().equals(market.id()) && z.areaKm2() != null)) {
            throw new Conflict("not_ready", NO_ZONE);
        }
        var blocking = blocking(market);
        if (!blocking.isEmpty() && !request.override()) {
            throw new Conflict("not_ready", NOT_READY);
        }
        store.updateRequest(approved);
        editor.stage(market.id(), LaunchStatus.LIVE);
        var shown = pilots.marketLaunched(market.id(), new PilotCohort.Actor(actor.userId(), actor.role()));
        var now = clock.instant();
        store.insertEvent(
                new MarketEvent(Ids.next(), market.id(), "launched", request.id(), actor.userId(), null, now));
        audit(
                actor,
                "region.stage_changed",
                market.id(),
                Map.of("stage", "pilot"),
                Map.of("stage", "live", "requestId", request.id()));
        var after = new LinkedHashMap<String, Object>();
        after.put("requestId", request.id());
        after.put("requestedBy", request.requestedBy());
        after.put("override", request.override());
        after.put("blocking", blocking);
        after.put("businessesShown", shown);
        audit(actor, "golive.launch_approved", market.id(), null, after);
        refreshAfterCommit();
        return view(market);
    }

    @Override
    public Checklist close(String marketId, String requestId, @Nullable String reason, Actor actor) {
        var market = market(marketId);
        var request = store.lockRequest(requestId)
                .filter(r -> r.marketId().equals(market.id()))
                .orElseThrow(() -> new NotFound("launch request", requestId));
        var closed = request.close(actor.userId(), reason, clock.instant());
        store.updateRequest(closed);
        audit(
                actor,
                closed.state() == LaunchRequest.State.WITHDRAWN ? "golive.launch_withdrawn" : "golive.launch_rejected",
                market.id(),
                null,
                Map.of("requestId", request.id()));
        return view(market);
    }

    @Override
    public Checklist rollback(String marketId, String reason, String confirm, Actor actor) {
        var market = market(marketId);
        confirm(market, confirm);
        var why = reason.strip();
        if (why.length() < 10 || why.length() > 500) {
            throw RuleViolation.of("reason", "length", ROLLBACK_REASON);
        }
        var ref = lock(market);
        if (ref.stage() != LaunchStatus.LIVE) {
            throw new Conflict("not_live", NOT_LIVE);
        }
        editor.stage(market.id(), LaunchStatus.PILOT);
        // nothing is cancelled: orders and bookings in progress carry on; new public discovery stops
        var hidden = pilots.marketPaused(market.id(), new PilotCohort.Actor(actor.userId(), actor.role()));
        store.insertEvent(
                new MarketEvent(Ids.next(), market.id(), "rolled_back", null, actor.userId(), why, clock.instant()));
        audit(actor, "region.stage_changed", market.id(), Map.of("stage", "live"), Map.of("stage", "pilot"));
        audit(actor, "golive.rolled_back", market.id(), null, Map.of("reason", why, "businessesHidden", hidden));
        refreshAfterCommit();
        return view(market);
    }

    @Override
    public Checklist startHypercare(
            String marketId,
            @Nullable LocalDate startsOn,
            List<String> primaries,
            List<String> secondaries,
            List<String> businessContacts,
            Actor actor) {
        var market = market(marketId);
        if (lock(market).stage() != LaunchStatus.LIVE) {
            throw new Conflict("not_live", HYPERCARE_NOT_LIVE);
        }
        var today = LocalDate.now(clock.withZone(market.zone()));
        var start = startsOn == null ? today : startsOn;
        if (start.isBefore(today) || start.isAfter(today.plusDays(HypercareRotation.DAYS))) {
            throw RuleViolation.of("startsOn", "range", HYPERCARE_START);
        }
        requireStaff("primaries", primaries);
        requireStaff("secondaries", secondaries);
        requireStaff("businessContacts", businessContacts);
        var plan = HypercareRotation.plan(start, primaries, secondaries, businessContacts);
        var end = start.plusDays(HypercareRotation.DAYS - 1);
        if (store.hypercare(market.id()).stream()
                .anyMatch(d -> !d.day().isBefore(start) && !d.day().isAfter(end))) {
            throw new Conflict("hypercare_exists", HYPERCARE_EXISTS);
        }
        var now = clock.instant();
        var days = new ArrayList<HypercareDay>();
        for (var d : plan) {
            var from = d.date().atStartOfDay(market.zone()).toInstant();
            var to = d.date().plusDays(1).atStartOfDay(market.zone()).toInstant();
            var primary = rota.add(d.primary(), from, to, duty(market, "primary"), actor.userId());
            var secondary = rota.add(d.secondary(), from, to, duty(market, "secondary"), actor.userId());
            days.add(new HypercareDay(
                    market.id(),
                    d.date(),
                    d.primary(),
                    d.secondary(),
                    d.business(),
                    primary.id(),
                    secondary.id(),
                    actor.userId(),
                    now));
        }
        store.insertHypercare(days);
        audit(
                actor,
                "golive.hypercare_created",
                market.id(),
                null,
                Map.of("startsOn", start.toString(), "days", days.size()));
        return view(market);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** The paged duty on the on-call rota (staff-facing data: the market's own name, never one written in code). */
    private static String duty(MarketProfile market, String role) {
        return "Hypercare · " + market.city() + " · " + role;
    }

    private void requireStaff(String field, List<String> people) {
        for (var id : people) {
            if (staff.member(id).filter(m -> m.roles().contains("staff")).isEmpty()) {
                throw RuleViolation.of(field, "staff", NOT_STAFF);
            }
        }
    }

    private List<String> blocking(MarketProfile market) {
        return gates.evaluate(market, store.latestRecords(market.id())).stream()
                .filter(r -> r.required() && !r.status().clears())
                .map(r -> r.gate().code())
                .toList();
    }

    private MarketProfile market(String marketId) {
        return regions.marketById(marketId).orElseThrow(() -> new NotFound(MARKET, marketId));
    }

    private RegionRef lock(MarketProfile market) {
        return editor.lock(market.id()).orElseThrow(() -> new NotFound(MARKET, market.id()));
    }

    private static void confirm(MarketProfile market, String confirm) {
        if (!confirm.strip().equalsIgnoreCase(market.city().strip())) {
            throw RuleViolation.of("confirm", "match", CONFIRM);
        }
    }

    /** The market's stage as stored now (the region model's cache may be a minute behind). */
    private LaunchStatus stage(MarketProfile market) {
        return editor.province(market.province())
                .flatMap(p -> editor.markets(p.id()).stream()
                        .filter(m -> m.id().equals(market.id()))
                        .findFirst())
                .map(RegionEditor.Market::stage)
                .orElse(market.status());
    }

    private static boolean https(String url) {
        if (url.length() > 500 || !url.startsWith("https://")) {
            return false;
        }
        try {
            return URI.create(url).getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Checklist view(MarketProfile market) {
        var now = clock.instant();
        var results = gates.evaluate(market, store.latestRecords(market.id()));
        var names = new HashMap<String, Person>();
        var gateViews = results.stream()
                .map(r -> {
                    var rec = r.record();
                    return new GateView(
                            r.gate().code(),
                            r.gate().kind().code(),
                            r.gate().owner(),
                            r.required(),
                            r.status().code(),
                            r.code(),
                            r.params(),
                            rec == null ? null : rec.evidence(),
                            rec == null ? null : rec.evidenceUrl(),
                            rec == null ? null : rec.source(),
                            rec == null ? null : person(names, rec.recordedBy()),
                            rec == null ? null : rec.recordedAt(),
                            r.recordable(),
                            r.gate().runbook());
                })
                .toList();
        var blocking = results.stream()
                .filter(r -> r.required() && !r.status().clears())
                .map(r -> r.gate().code())
                .toList();
        var requests = store.requests(market.id(), 10).stream()
                .map(r -> r.lapsed(now) ? r.expire() : r)
                .map(r -> request(names, r))
                .toList();
        var open = requests.stream()
                .filter(r -> r.state().equals("pending"))
                .findFirst()
                .orElse(null);
        var events = store.events(market.id(), 20).stream()
                .map(e ->
                        new EventView(e.kind(), person(names, e.actorId()), e.occurredAt(), e.reason(), e.requestId()))
                .toList();
        var days = store.hypercare(market.id());
        Hypercare hypercare = null;
        if (!days.isEmpty()) {
            var sorted = days.stream()
                    .sorted(Comparator.comparing(HypercareDay::day))
                    .toList();
            hypercare = new Hypercare(
                    sorted.getFirst().day(),
                    sorted.getLast().day(),
                    sorted.stream()
                            .map(d -> new HypercareDayView(
                                    d.day(),
                                    person(names, d.primary()),
                                    person(names, d.secondary()),
                                    person(names, d.business())))
                            .toList());
        }
        return new Checklist(
                new Market(
                        market.id(),
                        market.city(),
                        market.province(),
                        stage(market).code(),
                        market.language().frenchFirst(),
                        market.zone().getId()),
                now,
                gateViews,
                blocking,
                blocking.isEmpty(),
                open,
                requests,
                events,
                hypercare);
    }

    private RequestView request(Map<String, Person> names, LaunchRequest r) {
        return new RequestView(
                r.id(),
                r.state().code(),
                person(names, r.requestedBy()),
                r.requestedAt(),
                r.expiresAt(),
                r.note(),
                r.override(),
                r.overrideReason(),
                r.blocking(),
                r.decidedBy() == null ? null : person(names, r.decidedBy()),
                r.decidedAt(),
                r.decisionNote());
    }

    private Person person(Map<String, Person> names, String userId) {
        return names.computeIfAbsent(
                userId,
                id -> new Person(
                        id, staff.member(id).map(StaffDirectory.Member::name).orElse(id)));
    }

    private void audit(
            Actor actor,
            String action,
            String marketId,
            @Nullable Map<String, ?> before,
            @Nullable Map<String, ?> after) {
        audit.record(new AuditTrail.Entry(null, actor.userId(), actor.role(), action, MARKET, marketId, before, after));
    }

    private void refreshAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    regions.refresh();
                }
            });
        } else {
            regions.refresh();
        }
    }
}
