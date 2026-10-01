package ca.northline.console.application;

import ca.northline.region.api.DeliveryZones;
import ca.northline.region.api.Regions;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.RuleViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class DeliveryMapService implements ViewDeliveryMap {

    private final Regions regions;
    private final DeliveryZones zones;
    private final Basemaps basemaps;

    @Override
    public DeliveryMap map(String marketId) {
        var market = regions.marketById(marketId.strip())
                .orElseThrow(() -> RuleViolation.of("market", "exists", PlaceFilter.UNKNOWN_MARKET));
        return new DeliveryMap(
                new Market(market.id(), market.city(), market.province(), market.lat(), market.lng()),
                zones.inMarket(market.id()),
                basemaps.current());
    }
}
