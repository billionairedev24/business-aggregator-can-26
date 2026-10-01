package ca.northline.console.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.RegionEditor;
import ca.northline.region.api.RegionEditor.ProvinceRow;
import ca.northline.region.api.RegionEditor.RegionRef;
import ca.northline.region.api.RegionEditor.ZoneInput;
import ca.northline.region.api.Regions;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link Switchboard} over the region module's {@link RegionEditor}: stage rules (a market is never more open than its province; going
 * live needs the checklist), the platform audit log in the same transaction, and {@link Regions#refresh()} once it
 * committed so this instance serves the change at once (others within {@code REGION_CACHE_TTL}).
 */
@Service
@Transactional
@RequiredArgsConstructor
class SwitchboardService implements Switchboard {

    private static final Set<String> COURIER_MODELS = Set.of("own", "contracted", "hybrid");

    private final RegionEditor store;
    private final AuditTrail audit;
    private final Regions regions;

    @Override
    @Transactional(readOnly = true)
    public Board board() {
        return new Board(store.provinces().stream().map(this::view).toList());
    }

    @Override
    public Province provinceStage(String code, LaunchStatus stage, String confirm, Actor actor) {
        var row =
                store.province(code.strip().toUpperCase(Locale.ROOT)).orElseThrow(() -> new NotFound("province", code));
        if (!confirm.strip().equalsIgnoreCase(row.code())) {
            throw RuleViolation.of("confirm", "match", CONFIRM_PROVINCE);
        }
        var ref = store.lock(row.id()).orElseThrow();
        var view = view(row);
        if (stage == LaunchStatus.LIVE && ref.stage() != LaunchStatus.LIVE && !view.ready()) {
            throw new Conflict("not_ready", NOT_READY);
        }
        if (stage == ref.stage()) {
            return view;
        }
        store.stage(row.id(), stage);
        // a province's stage is the ceiling: markets above it come down with it
        var lowered = new LinkedHashMap<String, String>();
        for (var m : store.markets(row.id())) {
            if (m.stage().above(stage)) {
                store.stage(m.id(), stage);
                lowered.put(m.id(), m.stage().code());
            }
        }
        var after = new HashMap<String, Object>(Map.of("stage", stage.code()));
        if (!lowered.isEmpty()) {
            after.put("marketsLowered", lowered);
        }
        record(
                actor,
                "region.stage_changed",
                "province",
                row.id(),
                Map.of("stage", ref.stage().code()),
                after);
        return reload(row.code());
    }

    @Override
    public Province courierModel(String code, String model, Actor actor) {
        var row =
                store.province(code.strip().toUpperCase(Locale.ROOT)).orElseThrow(() -> new NotFound("province", code));
        if (!COURIER_MODELS.contains(model)) {
            throw RuleViolation.of("courierModel", "format", COURIER_MODEL);
        }
        store.lock(row.id());
        if (!model.equals(row.courierModel())) {
            store.courierModel(row.id(), model);
            var before = new HashMap<String, Object>();
            if (row.courierModel() != null) {
                before.put("courierModel", row.courierModel());
            }
            record(actor, "region.courier_model_changed", "province", row.id(), before, Map.of("courierModel", model));
        }
        return reload(row.code());
    }

    @Override
    public Province marketStage(String marketId, LaunchStatus stage, String confirm, Actor actor) {
        var ref = market(marketId);
        if (ref.city() == null || !confirm.strip().equalsIgnoreCase(ref.city().strip())) {
            throw RuleViolation.of("confirm", "match", CONFIRM_MARKET);
        }
        var province =
                store.lock(java.util.Objects.requireNonNull(ref.parentId())).orElseThrow();
        if (stage.above(province.stage())) {
            throw RuleViolation.of("stage", "province", ABOVE_PROVINCE);
        }
        if (stage == ref.stage()) {
            return reload(ref.province());
        }
        if (stage == LaunchStatus.LIVE) {
            var zones = store.zones(province.id()).stream()
                    .filter(z -> z.marketId().equals(ref.id()) && z.areaKm2() != null)
                    .count();
            if (zones == 0) {
                throw new Conflict("not_ready", MARKET_NOT_READY);
            }
        }
        store.stage(ref.id(), stage);
        record(
                actor,
                "region.stage_changed",
                "market",
                ref.id(),
                Map.of("stage", ref.stage().code()),
                Map.of("stage", stage.code()));
        return reload(ref.province());
    }

    @Override
    public Province addMarket(NewMarket market, Actor actor) {
        var row = store.province(market.province().strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> RuleViolation.of("province", "exists", "Choose a province from the list."));
        var city = market.city().strip();
        if (city.length() < 2 || city.length() > 60) {
            throw RuleViolation.of("city", "length", CITY);
        }
        // Canada's bounding box: a centre outside it is a typo (or lat/lng swapped)
        if (market.lat() < 41.5 || market.lat() > 83.5 || market.lng() < -141.1 || market.lng() > -52.5) {
            throw RuleViolation.of("lat", "range", CENTRE);
        }
        if (market.radiusKm() < 1 || market.radiusKm() > 200) {
            throw RuleViolation.of("radiusKm", "range", RADIUS);
        }
        store.lock(row.id());
        if (store.cityTaken(row.id(), city)) {
            throw new Conflict("market_exists", CITY_TAKEN);
        }
        var id = store.insertMarket(row.id(), row.code(), city, market.lat(), market.lng(), market.radiusKm());
        record(actor, "region.market_added", "market", id, null, Map.of("province", row.code(), "stage", "off"));
        return reload(row.code());
    }

    @Override
    public Province saveZone(@Nullable String zoneId, ZoneInput zone, Actor actor) {
        var name = zone.name().strip();
        if (name.isEmpty() || name.length() > 60) {
            throw RuleViolation.of("name", "length", ZONE_NAME);
        }
        if (zone.runsPerDay() != null && (zone.runsPerDay() < 0 || zone.runsPerDay() > 24)) {
            throw RuleViolation.of("runsPerDay", "range", RUNS);
        }
        money("feeStdCents", zone.feeStdCents());
        money("feePlusCents", zone.feePlusCents());
        money("minBasketCents", zone.minBasketCents());
        var market = market(zone.marketId());
        if (zoneId != null) {
            var existing = store.zone(zoneId).orElseThrow(() -> new NotFound("zone", zoneId));
            if (!existing.provinceId().equals(market.parentId())) {
                throw RuleViolation.of("marketId", "province", ZONE_MARKET);
            }
        }
        store.lock(java.util.Objects.requireNonNull(market.parentId()));
        String id;
        try {
            id = store.saveZone(
                    zoneId,
                    new ZoneInput(
                            market.id(),
                            name,
                            zone.runsPerDay(),
                            zone.feeStdCents(),
                            zone.feePlusCents(),
                            zone.minBasketCents(),
                            zone.boundary()));
        } catch (IllegalArgumentException e) {
            throw RuleViolation.of("boundary", "format", BOUNDARY);
        }
        var after = new HashMap<String, Object>();
        after.put("marketId", market.id());
        after.put("boundary", zone.boundary() != null);
        record(actor, zoneId == null ? "region.zone_created" : "region.zone_updated", "zone", id, null, after);
        return reload(market.province());
    }

    @Override
    public Province removeZone(String zoneId, Actor actor) {
        var zone = store.zone(zoneId).orElseThrow(() -> new NotFound("zone", zoneId));
        var province = store.lock(zone.provinceId()).orElseThrow();
        // a live market keeps at least one zone: removing the last one would leave it delivering nowhere
        var market = store.lock(zone.marketId()).orElseThrow();
        var left = store.zones(province.id()).stream()
                .filter(z -> z.marketId().equals(market.id()) && !z.id().equals(zoneId) && z.areaKm2() != null)
                .count();
        if (market.stage() == LaunchStatus.LIVE && left == 0) {
            throw new Conflict("last_zone", LAST_ZONE);
        }
        store.deleteZone(zoneId);
        record(actor, "region.zone_removed", "zone", zoneId, Map.of("marketId", zone.marketId()), null);
        return reload(province.province());
    }

    private static void money(String field, @Nullable Long cents) {
        if (cents != null && cents < 0) {
            throw RuleViolation.of(field, "range", MONEY);
        }
    }

    private RegionRef market(String marketId) {
        return store.lock(marketId)
                .filter(r -> r.kind().equals("market"))
                .orElseThrow(() -> RuleViolation.of("marketId", "exists", "Choose a market from the list."));
    }

    private Province reload(String code) {
        afterCommit();
        return view(store.province(code).orElseThrow());
    }

    private Province view(ProvinceRow row) {
        var markets = store.markets(row.id());
        var zones = store.zones(row.id());
        var checklist = new LinkedHashMap<String, Boolean>();
        checklist.put("taxProfile", !row.tax().isEmpty());
        checklist.put("holidays", !row.holidays().isEmpty());
        checklist.put("registries", !row.registries().isEmpty());
        checklist.put(
                "marketWithZones",
                markets.stream()
                        .anyMatch(m ->
                                zones.stream().anyMatch(z -> z.marketId().equals(m.id()) && z.areaKm2() != null)));
        return new Province(
                row.id(),
                row.code(),
                row.names(),
                row.stage(),
                row.languages(),
                row.courierModel(),
                row.tax(),
                row.timeZones(),
                row.holidays(),
                row.privacyLaw(),
                row.registries(),
                row.waitlist(),
                markets,
                zones,
                checklist);
    }

    private void record(
            Actor actor,
            String action,
            String targetType,
            String targetId,
            @Nullable Map<String, ?> before,
            @Nullable Map<String, ?> after) {
        audit.record(
                new AuditTrail.Entry(null, actor.userId(), actor.role(), action, targetType, targetId, before, after));
    }

    /** Re-reads the region model once the change committed (or now, outside a transaction). */
    private void afterCommit() {
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
