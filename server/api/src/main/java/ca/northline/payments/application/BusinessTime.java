package ca.northline.payments.application;

import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.Regions;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Business time (S-134): every date a merchant sees — payout days, report buckets, months, weeks — is in the
 * business's own zone (its market's, else its province's; region model). Northline's own bookkeeping (tax reporting
 * quarters, nightly jobs) is in the configured platform zone.
 */
@Component
@RequiredArgsConstructor
public class BusinessTime {

    private final MerchantPlaces places;
    private final Regions regions;

    public ZoneId of(String merchantId) {
        return places.of(merchantId).zone();
    }

    /** The zones of many businesses at once (one read for all), by id. */
    public Map<String, ZoneId> ofAll(Collection<String> merchantIds) {
        var zones = new LinkedHashMap<String, ZoneId>();
        places.ofAll(merchantIds).forEach((id, place) -> zones.put(id, place.zone()));
        return zones;
    }

    public ZoneId platform() {
        return regions.platformZone();
    }
}
