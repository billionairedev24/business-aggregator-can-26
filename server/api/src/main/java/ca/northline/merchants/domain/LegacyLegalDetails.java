package ca.northline.merchants.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Legal details written before the region-neutral keys (V373, DECISIONS "2026-10-19 — Province legal details"): V373
 * rewrote the rows that existed, and this maps what an api of the previous release (or an older client) may still
 * write, on every read and before every save. Keys and the structure code only — values are kept as they are.
 */
public final class LegacyLegalDetails {
    private LegacyLegalDetails() {}

    static final Map<String, String> KEYS = Map.of(
            "alberta_corporate_access_number", "provincial_registry_number",
            "alberta_extra_provincial_registration", "extra_provincial_registration_number");
    static final String ATTORNEY = "attorney_for_service";
    static final Map<String, String> ATTORNEY_KEYS = Map.of("alberta_address", "address_in_province");

    /** The details with the old keys renamed (a new key already present wins); the same map when nothing is old. */
    public static Map<String, Object> upgrade(@Nullable Map<String, Object> details) {
        if (details == null) {
            return Map.of();
        }
        var old = details.keySet().stream().anyMatch(KEYS::containsKey)
                || BusinessStructure.LEGACY_PROVINCIAL.equals(details.get("structure"))
                || (details.get(ATTORNEY) instanceof Map<?, ?> a
                        && a.keySet().stream().anyMatch(ATTORNEY_KEYS::containsKey));
        if (!old) {
            return details;
        }
        var out = new LinkedHashMap<String, Object>();
        details.forEach((key, value) -> {
            var renamed = KEYS.getOrDefault(key, key);
            if (!renamed.equals(key) && details.containsKey(renamed)) {
                return;
            }
            out.put(renamed, key.equals(ATTORNEY) && value instanceof Map<?, ?> m ? attorney(m) : value);
        });
        if (BusinessStructure.LEGACY_PROVINCIAL.equals(out.get("structure"))) {
            out.put("structure", BusinessStructure.CORP_PROV.code());
        }
        return out;
    }

    private static Map<String, Object> attorney(Map<?, ?> attorney) {
        var out = new LinkedHashMap<String, Object>();
        attorney.forEach((k, v) -> {
            var key = String.valueOf(k);
            var renamed = ATTORNEY_KEYS.getOrDefault(key, key);
            if (renamed.equals(key) || !attorney.containsKey(renamed)) {
                out.put(renamed, v);
            }
        });
        return out;
    }
}
