package ca.northline.messaging.web;

import ca.northline.messaging.application.PushDevices.Device;
import ca.northline.messaging.application.PushDevices.ManagePushDevices;
import ca.northline.messaging.application.PushDevices.Registration;
import ca.northline.messaging.domain.PushDeviceRules;
import ca.northline.messaging.domain.PushDeviceRules.App;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The push device registry of the consumer and courier apps (S-102; docs/runbooks/push.md). Only DPoP-bound app tokens
 * reach it (SecurityConfig); the app is the token's: scope {@code courier} = the courier app, else the consumer app.
 *
 * <pre>
 * PUT    /api/v1/me/devices/{installationId}   {platform, token?, locale, appVersion, permission} → the device
 *                                              (register at sign-in, refresh at every start and on a new token)
 * DELETE /api/v1/me/devices/{installationId}   sign-out → 204; 404 when the installation isn't the caller's
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me/devices")
@RequiredArgsConstructor
class MyDevicesController {

    private final ManagePushDevices devices;

    record DeviceRequest(
            @NotBlank(message = PushDeviceRules.PLATFORM)
            @Pattern(regexp = PushDeviceRules.PLATFORMS, message = PushDeviceRules.PLATFORM)
            String platform,

            @Nullable
            @Size(min = PushDeviceRules.TOKEN_MIN, max = PushDeviceRules.TOKEN_MAX, message = PushDeviceRules.TOKEN)
            @Pattern(regexp = "[\\x21-\\x7e]+", message = PushDeviceRules.TOKEN)
            String token,

            @NotBlank(message = PushDeviceRules.LANGUAGE)
            @Pattern(regexp = PushDeviceRules.LANGUAGES, message = PushDeviceRules.LANGUAGE)
            String locale,

            @NotBlank(message = PushDeviceRules.VERSION)
            @Size(max = PushDeviceRules.VERSION_MAX, message = PushDeviceRules.VERSION)
            String appVersion,

            @NotBlank(message = PushDeviceRules.PERMISSION)
            @Pattern(regexp = PushDeviceRules.PERMISSIONS, message = PushDeviceRules.PERMISSION)
            String permission) {}

    record DeviceResponse(
            String installationId, String app, String platform, String locale, String permission, Instant refreshedAt) {

        static DeviceResponse of(Device d) {
            return new DeviceResponse(
                    d.installationId(), d.app().code(), d.platform(), d.locale(), d.permission(), d.refreshedAt());
        }
    }

    @Operation(summary = "Register or refresh this app installation's push token")
    @PutMapping("/{installationId}")
    DeviceResponse put(CurrentUser user, @PathVariable String installationId, @Valid @RequestBody DeviceRequest body) {
        return DeviceResponse.of(devices.register(new Registration(
                user.userId(),
                App.ofScopes(user.scopes()),
                installationId,
                body.platform(),
                body.token(),
                body.locale(),
                body.appVersion(),
                body.permission())));
    }

    @Operation(summary = "Remove this app installation (sign-out)")
    @DeleteMapping("/{installationId}")
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable String installationId) {
        if (!devices.remove(user.userId(), App.ofScopes(user.scopes()), installationId)) {
            throw new NotFound("device", installationId);
        }
        return ResponseEntity.noContent().build();
    }
}
