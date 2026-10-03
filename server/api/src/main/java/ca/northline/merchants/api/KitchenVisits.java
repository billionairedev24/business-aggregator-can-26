package ca.northline.merchants.api;

import ca.northline.shared.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * S-120: kitchen visits — onboarding's {@code site_visit} check made real. Staff schedule a visit (a date, a staff
 * member or a named inspector), add photos on the day (stored through the merchants storage port as verification
 * documents), and record the outcome against the checklist. Passed → the check is verified; failed → the owner books
 * again. Where the kitchen's region or one of its categories requires it, the kitchen is approved only after a passed
 * visit. Every change is in the platform audit log.
 */
public interface KitchenVisits {

    String WHEN_REQUIRED = "Pick the visit's date and time, within the next 60 days.";
    String INSPECTOR_REQUIRED = "Choose who visits: a team member, or an inspector's name.";
    String INSPECTOR_NAME_LENGTH = "The inspector's name is at most 80 characters.";
    String NOT_A_KITCHEN = "Kitchen visits are for kitchens.";
    String PHOTO_TYPE = "Upload a JPEG or PNG photo under 10 MB.";

    /** The checklist's item codes, in order. */
    List<String> items();

    /** A kitchen's visits, newest first. */
    List<Visit> visits(String merchantId);

    /** Whether the kitchen is approved only after a passed visit (region or category rule). */
    boolean required(String merchantId);

    Visit schedule(Schedule command, PilotCohort.Actor actor);

    Visit addPhoto(String merchantId, String visitId, Upload photo, PilotCohort.Actor actor);

    /** @param checklist item code → {@code pass|fail|na}, every item */
    Visit record(
            String merchantId,
            String visitId,
            boolean passed,
            Map<String, String> checklist,
            @Nullable String note,
            PilotCohort.Actor actor);

    Visit cancel(String merchantId, String visitId, PilotCohort.Actor actor);

    Upload photo(String merchantId, String visitId, String photoId);

    record Schedule(
            String merchantId,
            @Nullable Instant at,
            @Nullable String inspectorId,
            @Nullable String inspectorName) {}

    record Upload(String fileName, String contentType, Bytes bytes) {}

    /**
     * @param status {@code scheduled|passed|failed|cancelled}
     * @param checklist item code → {@code pass|fail|na} once recorded
     */
    record Visit(
            String id,
            String merchantId,
            Instant scheduledAt,
            @Nullable String inspectorId,
            @Nullable String inspectorName,
            String status,
            Map<String, String> checklist,
            @Nullable String note,
            List<String> photoIds,
            @Nullable String recordedBy,
            @Nullable Instant recordedAt) {

        public Visit {
            checklist = Map.copyOf(checklist);
            photoIds = List.copyOf(photoIds);
        }
    }
}
