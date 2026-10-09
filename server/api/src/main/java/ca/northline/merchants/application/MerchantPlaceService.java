package ca.northline.merchants.application;

import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Markets;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.Regions;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link MerchantPlaces}: the business's onboarding province and city, resolved against the region model. */
@Service
@RequiredArgsConstructor
class MerchantPlaceService implements MerchantPlaces {

    private final MerchantDirectory directory;
    private final Regions regions;
    private final Markets markets;

    @Override
    public MerchantPlace of(String merchantId) {
        return place(directory.profile(merchantId));
    }

    @Override
    public Map<String, MerchantPlace> ofAll(Collection<String> merchantIds) {
        var profiles = directory.profiles(merchantIds);
        var places = new LinkedHashMap<String, MerchantPlace>();
        merchantIds.forEach(id -> places.put(id, place(Optional.ofNullable(profiles.get(id)))));
        return places;
    }

    private MerchantPlace place(Optional<MerchantDirectory.MerchantProfile> profile) {
        var own = profile.map(MerchantDirectory.MerchantProfile::province)
                .filter(p -> !p.isBlank())
                .map(p -> p.strip().toUpperCase(Locale.ROOT))
                .orElse(null);
        var province = own != null ? own : markets.defaultProvince();
        var city = profile.map(MerchantDirectory.MerchantProfile::city)
                .filter(c -> !c.isBlank())
                .orElse(null);
        var market = regions.market(city, province);
        var zone = market.map(MarketProfile::zone)
                .or(() -> regions.province(province).map(p -> p.zone()))
                .orElseGet(regions::platformZone);
        var name = regions.province(province);
        return new MerchantPlace(
                province,
                own != null,
                city,
                market.map(MarketProfile::id).orElse(null),
                zone,
                name.map(p -> p.name(Locale.ENGLISH)).orElse(""),
                name.map(p -> p.name(Locale.CANADA_FRENCH)).orElse(""),
                name.orElse(null));
    }
}
