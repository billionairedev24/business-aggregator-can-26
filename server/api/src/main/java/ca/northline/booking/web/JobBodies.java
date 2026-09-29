package ca.northline.booking.web;

import ca.northline.booking.domain.Approval;
import ca.northline.booking.domain.GeoPoint;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request bodies of the job flow. */
final class JobBodies {
    private JobBodies() {}

    /** Start travel / Check in: the device position when available (GPS check-in, dispute evidence). */
    record PositionBody(
            @DecimalMin(value = "-90", message = "Location is not a valid position.")
            @DecimalMax(value = "90", message = "Location is not a valid position.")
            @Nullable
            Double lat,

            @DecimalMin(value = "-180", message = "Location is not a valid position.")
            @DecimalMax(value = "180", message = "Location is not a valid position.")
            @Nullable
            Double lng) {

        @Nullable
        GeoPoint point() {
            return lat == null || lng == null ? null : new GeoPoint(lat, lng);
        }
    }

    /** Complete with photos + report. */
    record CompleteBody(
            @Size(max = 12, message = "At most 12 photos.") @Nullable
            List<String> photoMediaIds,

            @Size(max = 4000, message = "At most 4000 characters.") @Nullable
            String report,

            @Nullable Double lat,
            @Nullable Double lng) {

        @Nullable
        GeoPoint point() {
            return lat == null || lng == null ? null : new GeoPoint(lat, lng);
        }
    }

    /** Request extra parts approval. */
    record ApprovalBody(
            @NotBlank(message = Approval.DESCRIPTION_REQUIRED)
            @Size(max = Approval.DESCRIPTION_MAX, message = Approval.DESCRIPTION_TOO_LONG)
            String description,

            @NotNull(message = Approval.AMOUNT_REQUIRED) @Positive(message = Approval.AMOUNT_REQUIRED)
            Long amountCents) {}
}
