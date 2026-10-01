package ca.northline.region.application;

import ca.northline.region.api.Holiday;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Markets;
import ca.northline.region.api.PrivacyLaw;
import ca.northline.region.api.ProvinceProfile;
import ca.northline.region.api.Regions;
import ca.northline.region.application.MarketStore.ProfileRow;
import ca.northline.region.domain.HolidayRule;
import ca.northline.shared.CodedEnum;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * The region model (S-134): {@code region.regions} rows (V117, V130) read through {@link MarketStore}, kept for
 * {@code northline.region.cache-ttl} and overlaid with the configuration in {@link RegionProperties}. Implements
 * {@link Markets} on the same data, so search, checkout and the Location screen agree on what is served. A malformed
 * override stops the api at start; the rows are read on first use (after Flyway).
 */
@Slf4j
@Service
@EnableConfigurationProperties(RegionProperties.class)
class RegionCatalogue implements Regions, Markets {

    private static final Pattern CODE = Pattern.compile("^[A-Z]{2}$");

    private final MarketStore store;
    private final Map<String, @Nullable ZoneId> overrides;
    private final @Nullable String defaultProvince;
    private final ZoneId platformZone;
    private final long ttlNanos;

    private volatile @Nullable Snapshot snapshot;

    RegionCatalogue(MarketStore store, RegionProperties properties) {
        this.store = store;
        this.overrides = parse(properties.provinces());
        var code = properties.defaultProvince().strip().toUpperCase(Locale.ROOT);
        if (!code.isEmpty() && !CODE.matcher(code).matches()) {
            throw new IllegalStateException(
                    "REGION_DEFAULT_PROVINCE is a two-letter province or territory code, not: " + code);
        }
        this.defaultProvince = code.isEmpty() ? null : code;
        this.platformZone = properties.platformZone();
        this.ttlNanos = properties.cacheTtl().toNanos();
    }

    /** {@code AB=Zone/Id,BC} → code → zone override (null = the row's zone). */
    static Map<String, @Nullable ZoneId> parse(String spec) {
        var out = new LinkedHashMap<String, @Nullable ZoneId>();
        for (var entry : spec.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            var pair = entry.split("=", 2);
            var code = pair[0].strip().toUpperCase(Locale.ROOT);
            if (!CODE.matcher(code).matches()) {
                throw new IllegalStateException(
                        "REGION_PROVINCES entries are CODE or CODE=Time/Zone (a two-letter province or territory code),"
                                + " not: " + entry.strip());
            }
            try {
                out.put(code, pair.length == 2 ? ZoneId.of(pair[1].strip()) : null);
            } catch (DateTimeException e) {
                throw new IllegalStateException("REGION_PROVINCES: " + code + " has no valid time zone: " + pair[1], e);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    // ---- Regions ----------------------------------------------------------------------------------------------------

    @Override
    public List<ProvinceProfile> provinces() {
        return data().provinces();
    }

    @Override
    public Optional<ProvinceProfile> province(@Nullable String code) {
        return code == null
                ? Optional.empty()
                : Optional.ofNullable(data().byCode().get(code.strip().toUpperCase(Locale.ROOT)));
    }

    @Override
    public List<MarketProfile> markets() {
        return data().markets();
    }

    @Override
    public Optional<MarketProfile> market(@Nullable String city, @Nullable String province) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        var name = city.strip();
        return data().markets().stream()
                .filter(m -> m.city().equalsIgnoreCase(name))
                .filter(m ->
                        province == null || province.isBlank() || m.province().equalsIgnoreCase(province.strip()))
                .findFirst();
    }

    @Override
    public Optional<MarketProfile> marketById(@Nullable String id) {
        return id == null
                ? Optional.empty()
                : data().markets().stream().filter(m -> m.id().equals(id)).findFirst();
    }

    @Override
    public ZoneId zone(@Nullable String province, @Nullable String city) {
        return market(city, province)
                .map(MarketProfile::zone)
                .or(() -> province(province).map(ProvinceProfile::zone))
                .orElse(platformZone);
    }

    @Override
    public ZoneId platformZone() {
        return platformZone;
    }

    @Override
    public List<Holiday> holidays(@Nullable String province, int year) {
        return province(province).stream()
                .flatMap(p -> p.holidays().stream())
                .flatMap(key -> HolidayRule.of(key).stream())
                .map(rule -> new Holiday(
                        rule.key(), rule.in(year), rule.name(Locale.ENGLISH), rule.name(Locale.CANADA_FRENCH)))
                .sorted(Comparator.comparing(Holiday::date))
                .toList();
    }

    @Override
    public void refresh() {
        snapshot = load();
    }

    // ---- Markets (by province) --------------------------------------------------------------------------------------

    @Override
    public Set<String> served() {
        return data().served();
    }

    @Override
    public boolean serves(@Nullable String province) {
        return province != null && served().contains(province.strip().toUpperCase(Locale.ROOT));
    }

    @Override
    public @Nullable String defaultProvince() {
        return defaultProvince;
    }

    @Override
    public ZoneId zone(@Nullable String province) {
        var data = data();
        if (province != null) {
            var code = province.strip().toUpperCase(Locale.ROOT);
            var own = data.byCode().get(code);
            if (own != null && data.served().contains(code)) {
                return own.zone();
            }
        }
        var fallback = defaultProvince != null
                ? defaultProvince
                : data.served().stream().findFirst().orElse(null);
        return province(fallback).map(ProvinceProfile::zone).orElse(platformZone);
    }

    // ---- loading ----------------------------------------------------------------------------------------------------

    private record Snapshot(
            long loadedAt,
            List<ProvinceProfile> provinces,
            Map<String, ProvinceProfile> byCode,
            List<MarketProfile> markets,
            Set<String> served) {}

    private Snapshot data() {
        var current = snapshot;
        if (current == null || System.nanoTime() - current.loadedAt() > ttlNanos) {
            synchronized (this) {
                current = snapshot;
                if (current == null || System.nanoTime() - current.loadedAt() > ttlNanos) {
                    current = load();
                    snapshot = current;
                }
            }
        }
        return current;
    }

    private Snapshot load() {
        var rows = store.profiles();
        var byCode = new LinkedHashMap<String, ProvinceProfile>();
        for (var row : rows) {
            if (!row.region().market()) {
                var p = province(row);
                byCode.put(p.code(), p);
            }
        }
        var served = new LinkedHashSet<String>();
        overrides.keySet().stream().filter(byCode::containsKey).forEach(served::add);
        byCode.values().stream()
                .filter(p -> p.status().live())
                .map(ProvinceProfile::code)
                .forEach(served::add);
        var unknown =
                overrides.keySet().stream().filter(c -> !byCode.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            log.warn("REGION_PROVINCES names provinces without a region row: {}", unknown);
        }
        if (defaultProvince != null && !byCode.containsKey(defaultProvince)) {
            log.warn("REGION_DEFAULT_PROVINCE {} has no region row", defaultProvince);
        }
        var provinces = new ArrayList<>(byCode.values());
        var order = List.copyOf(served);
        provinces.sort(Comparator.comparingInt(p -> {
            var i = order.indexOf(p.code());
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
        var markets = rows.stream()
                .filter(r -> r.region().market())
                .map(r -> market(r, byCode))
                .toList();
        return new Snapshot(
                System.nanoTime(),
                List.copyOf(provinces),
                Collections.unmodifiableMap(byCode),
                markets,
                Collections.unmodifiableSet(served));
    }

    private ProvinceProfile province(ProfileRow row) {
        var r = row.region();
        var override = overrides.get(r.province());
        var zones = new ArrayList<ZoneId>();
        if (override != null) {
            zones.add(override);
        }
        row.timeZones().stream().map(ZoneId::of).filter(z -> !zones.contains(z)).forEach(zones::add);
        if (zones.isEmpty()) {
            zones.add(platformZone);
        }
        var status = overrides.containsKey(r.province())
                ? LaunchStatus.LIVE
                : LaunchStatus.valueOf(r.stage().name());
        return new ProvinceProfile(
                r.province(),
                r.nameEn(),
                r.nameFr() == null ? r.nameEn() : r.nameFr(),
                zones,
                status,
                row.privacyLaw() == null ? PrivacyLaw.PIPEDA : CodedEnum.fromCode(PrivacyLaw.class, row.privacyLaw()),
                row.registries(),
                row.holidays(),
                row.taxBps() == null ? 0 : row.taxBps());
    }

    private MarketProfile market(ProfileRow row, Map<String, ProvinceProfile> provinces) {
        var r = row.region();
        var province = provinces.get(r.province());
        var zone = !row.timeZones().isEmpty()
                ? ZoneId.of(row.timeZones().getFirst())
                : province != null ? province.zone() : platformZone;
        return new MarketProfile(
                r.id(),
                r.city() == null ? r.nameEn() : r.city(),
                r.province(),
                zone,
                r.lat(),
                r.lng(),
                LaunchStatus.valueOf(r.stage().name()),
                row.registries());
    }
}
