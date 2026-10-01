package ca.northline.messaging.web;

import ca.northline.messaging.application.CustomerNotifications.Change;
import ca.northline.messaging.application.CustomerNotifications.ManageCustomerNotifications;
import ca.northline.messaging.domain.CustomerNotificationPrefs;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The consumer's notification settings (S-59, design 06 account › notifications):
 *
 * <pre>
 * GET /api/v1/me/notifications   {events, channels, matrix, quietOn, quietFrom, quietTo, language, marketing}
 * PUT /api/v1/me/notifications   any of {matrix (changed cells), quietOn, quietFrom, quietTo, language, marketing}
 * </pre>
 *
 * Security alerts stay on every channel (422 when a request turns one off).
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
@RequiredArgsConstructor
class MyNotificationsController {

    private final ManageCustomerNotifications notifications;

    record PrefsResponse(
            List<String> events,
            List<String> channels,
            Map<String, Map<String, Boolean>> matrix,
            boolean quietOn,
            LocalTime quietFrom,
            LocalTime quietTo,
            String language,
            String marketing) {

        static PrefsResponse of(CustomerNotificationPrefs p) {
            return new PrefsResponse(
                    CustomerNotificationPrefs.events(),
                    CustomerNotificationPrefs.CHANNELS,
                    p.matrix(),
                    p.quietOn(),
                    p.quietFrom(),
                    p.quietTo(),
                    p.language(),
                    p.marketing());
        }
    }

    record PrefsRequest(
            @Nullable Map<String, Map<String, Boolean>> matrix,
            @Nullable Boolean quietOn,
            @Nullable LocalTime quietFrom,
            @Nullable LocalTime quietTo,
            @Nullable String language,
            @Nullable String marketing) {}

    @Operation(summary = "The caller's notification settings")
    @GetMapping
    ResponseEntity<PrefsResponse> get(CurrentUser user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PrefsResponse.of(notifications.view(user.userId())));
    }

    @Operation(summary = "Change the caller's notification settings")
    @PutMapping
    PrefsResponse put(CurrentUser user, @RequestBody PrefsRequest b) {
        return PrefsResponse.of(notifications.update(
                user.userId(),
                new Change(b.matrix(), b.quietOn(), b.quietFrom(), b.quietTo(), b.language(), b.marketing())));
    }
}
