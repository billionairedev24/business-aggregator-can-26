package ca.northline.loyalty.application;

import ca.northline.loyalty.application.LoyaltyUseCases.NearbyRewards;
import ca.northline.merchants.api.PublicDirectory;
import ca.northline.trust.api.ActiveRewards;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NearbyRewards}: the rewards running today (trust), kept for active businesses (merchants' public directory)
 * in the city asked about or within {@link #RADIUS_KM} of the point, nearest first. Only what a business page shows
 * anyway: its name, type, page and the reward's terms.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class NearbyRewardsService implements NearbyRewards {

    static final double RADIUS_KM = 25;

    private final ActiveRewards rewards;
    private final PublicDirectory directory;

    @Override
    public List<NearbyReward> near(@Nullable String city, @Nullable Double lat, @Nullable Double lng, int limit) {
        var wantedCity = city == null || city.isBlank() ? null : city.strip().toLowerCase(Locale.ROOT);
        var out = new ArrayList<NearbyReward>();
        for (var r : rewards.all()) {
            var business = directory.byId(r.merchantId()).orElse(null);
            if (business == null) {
                continue;
            }
            Double km = null;
            if (lat != null && lng != null && business.lat() != null && business.lng() != null) {
                km = haversineKm(lat, lng, business.lat(), business.lng());
            }
            var inCity = wantedCity != null
                    && business.city() != null
                    && business.city().strip().toLowerCase(Locale.ROOT).equals(wantedCity);
            var close = km != null && km <= RADIUS_KM;
            if (!inCity && !close && (wantedCity != null || lat != null)) {
                continue;
            }
            out.add(new NearbyReward(
                    r.merchantId(),
                    business.displayName(),
                    business.type(),
                    business.slug(),
                    r.multiplier(),
                    r.label(),
                    r.endsOn(),
                    km == null ? null : Math.round(km * 10) / 10.0));
        }
        out.sort(Comparator.comparingDouble((NearbyReward n) -> {
                    var km = n.distanceKm();
                    return km == null ? Double.MAX_VALUE : km;
                })
                .thenComparing(NearbyReward::endsOn)
                .thenComparing(NearbyReward::businessName));
        return out.stream().limit(Math.max(1, Math.min(limit, 24))).toList();
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        var dLat = Math.toRadians(lat2 - lat1);
        var dLng = Math.toRadians(lng2 - lng1);
        var a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
