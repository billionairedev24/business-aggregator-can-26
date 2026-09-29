package ca.northline.messaging.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.messaging.application.NotificationPreferences.UpdateNotificationMatrix;
import ca.northline.messaging.application.NotificationPreferences.ViewNotificationMatrix;
import ca.northline.messaging.domain.NotificationMatrix;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings › Notifications — the signed-in member's own matrix (every team member edits their own):
 *
 * <pre>
 * GET /api/v1/merchants/{merchantId}/settings/notifications   {events, channels, matrix, quietFrom, quietTo}
 * PUT /api/v1/merchants/{merchantId}/settings/notifications   {matrix: {event: {channel: bool}}} (changed cells only)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/settings/notifications")
@RequiredArgsConstructor
class NotificationMatrixController {

    private final ViewNotificationMatrix view;
    private final UpdateNotificationMatrix update;

    record MatrixResponse(
            List<String> events,
            List<String> channels,
            Map<String, Map<String, Boolean>> matrix,
            LocalTime quietFrom,
            LocalTime quietTo) {

        static MatrixResponse of(NotificationMatrix m) {
            return new MatrixResponse(
                    NotificationMatrix.events(), NotificationMatrix.CHANNELS, m.matrix(), m.quietFrom(), m.quietTo());
        }
    }

    record UpdateMatrixRequest(@NotNull Map<String, Map<String, Boolean>> matrix) {}

    @GetMapping
    @RequiresMerchant(VIEW)
    MatrixResponse get(@PathVariable String merchantId, CurrentMember member) {
        return MatrixResponse.of(view.view(member.userId()));
    }

    @PutMapping
    @RequiresMerchant(VIEW)
    MatrixResponse put(
            @PathVariable String merchantId, @Valid @RequestBody UpdateMatrixRequest body, CurrentMember member) {
        return MatrixResponse.of(update.update(member.userId(), body.matrix()));
    }
}
