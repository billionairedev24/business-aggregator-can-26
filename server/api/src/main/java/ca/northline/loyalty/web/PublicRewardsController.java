package ca.northline.loyalty.web;

import ca.northline.loyalty.application.LoyaltyUseCases.NearbyRewards;
import ca.northline.loyalty.application.LoyaltyUseCases.NearbyRewards.NearbyReward;
import ca.northline.shared.ListResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Rewards near you" (S-152; consumer home and the app): businesses funding extra points today.
 *
 * <pre>
 * GET /api/v1/public/rewards?city=&lat=&lng=&limit=     {items: [NearbyReward]} — public, cached 60 s
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class PublicRewardsController {

    private final NearbyRewards rewards;

    @GetMapping("/api/v1/public/rewards")
    ResponseEntity<ListResponse<NearbyReward>> near(
            @RequestParam(required = false) @Nullable String city,
            @RequestParam(required = false) @Nullable Double lat,
            @RequestParam(required = false) @Nullable Double lng,
            @RequestParam(defaultValue = "8") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(new ListResponse<>(rewards.near(city, lat, lng, limit)));
    }
}
