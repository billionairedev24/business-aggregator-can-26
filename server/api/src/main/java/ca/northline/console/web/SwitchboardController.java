package ca.northline.console.web;

import ca.northline.console.application.Switchboard;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.RegionEditor;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The province switchboard (S-84, design 03 {@code regions}): admins only (screen regions, action {@code province}).
 * Every change is audited and re-reads the region model after commit. A stage change carries {@code confirm}: the
 * province's code, or the market's name (the screen's confirmation step).
 *
 * <pre>
 * GET    /api/v1/console/regions                                   {provinces: [Province]}
 * POST   /api/v1/console/regions/provinces/{code}/stage {stage, confirm}   Province   409 not_ready
 * PUT    /api/v1/console/regions/provinces/{code}/courier-model {courierModel}   Province
 * POST   /api/v1/console/regions/markets {province, city, lat, lng, radiusKm}   Province   409 market_exists
 * POST   /api/v1/console/regions/markets/{marketId}/stage {stage, confirm}      Province   422 stage (above the province) · 409 not_ready
 * POST   /api/v1/console/regions/zones {marketId, name, runsPerDay?, feeStdCents?, feePlusCents?, minBasketCents?, boundary?}
 * PUT    /api/v1/console/regions/zones/{zoneId} (same body)                     Province
 * DELETE /api/v1/console/regions/zones/{zoneId}                                 Province   409 last_zone
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/regions")
@RequiredArgsConstructor
@RequiresConsole(value = ConsoleScreen.REGIONS, actions = ConsoleAction.PROVINCE)
class SwitchboardController {

    private final Switchboard switchboard;

    record StageRequest(
            @NotBlank(message = Switchboard.STAGE) String stage,

            @NotBlank(message = Switchboard.CONFIRM_PROVINCE)
            String confirm) {}

    record MarketStageRequest(
            @NotBlank(message = Switchboard.STAGE) String stage,
            @NotBlank(message = Switchboard.CONFIRM_MARKET) String confirm) {}

    record CourierModelRequest(
            @NotBlank(message = Switchboard.COURIER_MODEL) String courierModel) {}

    record MarketRequest(
            @NotBlank(message = "Choose a province from the list.")
            String province,

            @NotBlank(message = Switchboard.CITY) String city,
            @NotNull(message = Switchboard.CENTRE) Double lat,
            @NotNull(message = Switchboard.CENTRE) Double lng,
            @NotNull(message = Switchboard.RADIUS) Double radiusKm) {}

    record ZoneRequest(
            @NotBlank(message = Switchboard.ZONE_MARKET) String marketId,
            @NotBlank(message = Switchboard.ZONE_NAME) String name,
            @Nullable Integer runsPerDay,
            @Nullable Long feeStdCents,
            @Nullable Long feePlusCents,
            @Nullable Long minBasketCents,
            @Nullable String boundary) {

        RegionEditor.ZoneInput input() {
            return new RegionEditor.ZoneInput(
                    marketId,
                    name,
                    runsPerDay,
                    feeStdCents,
                    feePlusCents,
                    minBasketCents,
                    boundary == null || boundary.isBlank() ? null : boundary);
        }
    }

    @GetMapping
    @RequiresConsole(ConsoleScreen.REGIONS)
    ResponseEntity<Switchboard.Board> board() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(switchboard.board());
    }

    @PostMapping("/provinces/{code}/stage")
    Switchboard.Province provinceStage(
            @PathVariable String code, @Valid @RequestBody StageRequest body, CurrentStaff staff) {
        return switchboard.provinceStage(code, stage(body.stage()), body.confirm(), actor(staff));
    }

    @PutMapping("/provinces/{code}/courier-model")
    Switchboard.Province courierModel(
            @PathVariable String code, @Valid @RequestBody CourierModelRequest body, CurrentStaff staff) {
        return switchboard.courierModel(code, body.courierModel(), actor(staff));
    }

    @PostMapping("/markets")
    Switchboard.Province addMarket(@Valid @RequestBody MarketRequest body, CurrentStaff staff) {
        return switchboard.addMarket(
                new Switchboard.NewMarket(body.province(), body.city(), body.lat(), body.lng(), body.radiusKm()),
                actor(staff));
    }

    @PostMapping("/markets/{marketId}/stage")
    Switchboard.Province marketStage(
            @PathVariable String marketId, @Valid @RequestBody MarketStageRequest body, CurrentStaff staff) {
        return switchboard.marketStage(marketId, stage(body.stage()), body.confirm(), actor(staff));
    }

    @PostMapping("/zones")
    Switchboard.Province createZone(@Valid @RequestBody ZoneRequest body, CurrentStaff staff) {
        return switchboard.saveZone(null, body.input(), actor(staff));
    }

    @PutMapping("/zones/{zoneId}")
    Switchboard.Province updateZone(
            @PathVariable String zoneId, @Valid @RequestBody ZoneRequest body, CurrentStaff staff) {
        return switchboard.saveZone(zoneId, body.input(), actor(staff));
    }

    @DeleteMapping("/zones/{zoneId}")
    Switchboard.Province removeZone(@PathVariable String zoneId, CurrentStaff staff) {
        return switchboard.removeZone(zoneId, actor(staff));
    }

    private static LaunchStatus stage(String code) {
        return Arrays.stream(LaunchStatus.values())
                .filter(s -> s.code().equals(code))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("stage", "format", Switchboard.STAGE));
    }

    private static Switchboard.Actor actor(CurrentStaff staff) {
        return new Switchboard.Actor(staff.userId(), staff.roleCodes());
    }
}
