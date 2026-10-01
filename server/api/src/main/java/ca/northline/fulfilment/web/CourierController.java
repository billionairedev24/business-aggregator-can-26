package ca.northline.fulfilment.web;

import ca.northline.fulfilment.application.DispatchUseCases.CourierApp;
import ca.northline.fulfilment.application.DispatchUseCases.CourierView;
import ca.northline.fulfilment.application.DispatchUseCases.Ping;
import ca.northline.fulfilment.application.DispatchUseCases.RunView;
import ca.northline.fulfilment.application.DispatchUseCases.ShiftView;
import ca.northline.fulfilment.domain.DeliveryRules;
import ca.northline.shared.Bytes;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The courier app's API (S-86; the app is S-87). Scope {@code courier} on a DPoP-bound token (S-29, SecurityConfig);
 * the caller must be an active courier (403 {@code not_a_courier}). A courier sees and moves only their own run.
 *
 * <pre>
 * GET  /api/v1/courier/me                        the courier, status and the shift that is on
 * GET  /api/v1/courier/shifts                    {items: [shift]} not yet ended
 * POST /api/v1/courier/shifts/{id}/start         from 15 min before its start → available
 * POST /api/v1/courier/shifts/{id}/end           409 run_open while a run isn't done → offline
 * GET  /api/v1/courier/run                       the run with its ordered stops; 204 without one
 * POST /api/v1/courier/stops/{id}/arrive
 * POST /api/v1/courier/stops/{id}/pickup         {scanOk}; 409 not_packed
 * POST /api/v1/courier/stops/{id}/proof          multipart kind=photo|signature, file (JPG/PNG/WebP ≤ 5 MB)
 * POST /api/v1/courier/stops/{id}/dropoff        {proof: photo|signature|pin, pin?}; 409 not_picked_up / proof_missing
 * POST /api/v1/courier/location                  S-88 {lat, lng, heading?} while on shift → {acceptedAt, nextAfterMs};
 *                                                429 too_many_pings (Retry-After) faster than every 2 s
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/courier")
@RequiredArgsConstructor
class CourierController {

    private final CourierApp app;

    record PickupRequest(boolean scanOk) {}

    record DropoffRequest(
            @NotBlank(message = DeliveryRules.PROOF_REQUIRED)
            @Pattern(regexp = "photo|signature|pin", message = DeliveryRules.PROOF_REQUIRED)
            String proof,

            @Nullable @Pattern(regexp = "\\s*\\d{4}\\s*", message = DeliveryRules.PIN_REQUIRED)
            String pin) {}

    record LocationRequest(
            @NotNull(message = DeliveryRules.POSITION)
            @DecimalMin(value = "-90", message = DeliveryRules.POSITION)
            @DecimalMax(value = "90", message = DeliveryRules.POSITION)
            Double lat,

            @NotNull(message = DeliveryRules.POSITION)
            @DecimalMin(value = "-180", message = DeliveryRules.POSITION)
            @DecimalMax(value = "180", message = DeliveryRules.POSITION)
            Double lng,

            @Nullable
            @DecimalMin(value = "0", message = DeliveryRules.POSITION)
            @DecimalMax(value = "360", message = DeliveryRules.POSITION)
            Double heading) {}

    @PostMapping("/location")
    Ping location(CurrentUser user, @Valid @RequestBody LocationRequest body) {
        return app.ping(user.userId(), body.lat(), body.lng(), body.heading());
    }

    @GetMapping("/me")
    CourierView me(CurrentUser user) {
        return app.me(user.userId());
    }

    @GetMapping("/shifts")
    ListResponse<ShiftView> shifts(CurrentUser user) {
        return new ListResponse<>(app.shifts(user.userId()));
    }

    @PostMapping("/shifts/{shiftId}/start")
    ShiftView start(CurrentUser user, @PathVariable String shiftId) {
        return app.startShift(user.userId(), shiftId);
    }

    @PostMapping("/shifts/{shiftId}/end")
    ShiftView end(CurrentUser user, @PathVariable String shiftId) {
        return app.endShift(user.userId(), shiftId);
    }

    @GetMapping("/run")
    ResponseEntity<RunView> run(CurrentUser user) {
        return app.myRun(user.userId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/stops/{stopId}/arrive")
    RunView arrive(CurrentUser user, @PathVariable String stopId) {
        return app.arrive(user.userId(), stopId);
    }

    @PostMapping("/stops/{stopId}/pickup")
    RunView pickup(
            CurrentUser user,
            @PathVariable String stopId,
            @RequestBody(required = false) @Nullable PickupRequest body) {
        return app.pickUp(user.userId(), stopId, body != null && body.scanOk());
    }

    @PostMapping(path = "/stops/{stopId}/proof", consumes = "multipart/form-data")
    RunView proof(
            CurrentUser user,
            @PathVariable String stopId,
            @RequestParam("kind") String kind,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        return app.proof(user.userId(), stopId, kind, Bytes.of(file.getBytes()));
    }

    @PostMapping("/stops/{stopId}/dropoff")
    RunView dropoff(CurrentUser user, @PathVariable String stopId, @Valid @RequestBody DropoffRequest body) {
        return app.dropOff(user.userId(), stopId, body.proof(), body.pin());
    }
}
