package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * S-120: a visit to a kitchen before it sells ({@code merchants.kitchen_visits}) — onboarding's {@code site_visit}
 * check made real: someone from Northline (or an inspector Northline works with) goes on a date, walks the checklist,
 * takes photos and records the outcome. A passed visit verifies the check.
 */
public record KitchenVisit(
        String id,
        String merchantId,
        Instant scheduledAt,
        @Nullable String inspectorId,
        @Nullable String inspectorName,
        Status status,
        Map<Item, Mark> checklist,
        @Nullable String note,
        List<String> photoIds,
        String scheduledBy,
        @Nullable String recordedBy,
        @Nullable Instant recordedAt) {

    public static final String OUTCOME_REQUIRED = "Choose passed or failed.";
    public static final String CHECKLIST_INCOMPLETE = "Mark every item on the checklist.";
    public static final String PASSED_WITH_FAILS = "A visit with a failed item can't pass. Mark it failed.";
    public static final String FAILED_NEEDS_NOTE = "Say what has to be fixed before the next visit.";
    public static final int NOTE_MAX = 1000;
    public static final String NOTE_TOO_LONG = "The note is at most 1,000 characters.";
    public static final int PHOTOS_MAX = 12;
    public static final String TOO_MANY_PHOTOS = "At most 12 photos per visit.";

    public KitchenVisit {
        checklist = Map.copyOf(checklist);
        photoIds = List.copyOf(photoIds);
    }

    public enum Status implements CodedEnum {
        SCHEDULED,
        PASSED,
        FAILED,
        CANCELLED
    }

    /** One line of the visit checklist (docs/runbooks/pilot-onboarding.md § Kitchen visit checklist). */
    public enum Item implements CodedEnum {
        HANDWASHING,
        TEMPERATURES,
        SEPARATION,
        SANITATION,
        PESTS,
        ALLERGENS,
        PERMIT_DISPLAYED,
        FOOD_HANDLER,
        STORAGE,
        WASTE,
        PACKAGING
    }

    public enum Mark implements CodedEnum {
        PASS,
        FAIL,
        NA
    }

    public static KitchenVisit schedule(
            String id,
            String merchantId,
            Instant at,
            @Nullable String inspectorId,
            @Nullable String inspectorName,
            String by) {
        return new KitchenVisit(
                id,
                merchantId,
                at,
                inspectorId,
                inspectorName,
                Status.SCHEDULED,
                Map.of(),
                null,
                List.of(),
                by,
                null,
                null);
    }

    public KitchenVisit withPhoto(String documentId) {
        requireScheduled();
        if (photoIds.size() >= PHOTOS_MAX) {
            throw RuleViolation.of("file", "range", TOO_MANY_PHOTOS);
        }
        var photos = new ArrayList<>(photoIds);
        photos.add(documentId);
        return new KitchenVisit(
                id,
                merchantId,
                scheduledAt,
                inspectorId,
                inspectorName,
                status,
                checklist,
                note,
                photos,
                scheduledBy,
                recordedBy,
                recordedAt);
    }

    /**
     * Records the outcome. Every item is marked; a pass has no failed item; a fail names what to fix (the note goes to
     * the business through the onboarding checklist).
     */
    public KitchenVisit record(boolean passed, Map<Item, Mark> marks, @Nullable String newNote, String by, Instant at) {
        requireScheduled();
        var missing =
                Arrays.stream(Item.values()).filter(i -> !marks.containsKey(i)).toList();
        if (!missing.isEmpty()) {
            throw RuleViolation.of("checklist", "required", CHECKLIST_INCOMPLETE);
        }
        var anyFail = marks.containsValue(Mark.FAIL);
        if (passed && anyFail) {
            throw RuleViolation.of("outcome", "checklist", PASSED_WITH_FAILS);
        }
        var trimmed = newNote == null || newNote.isBlank() ? null : newNote.strip();
        if (!passed && trimmed == null) {
            throw RuleViolation.of("note", "required", FAILED_NEEDS_NOTE);
        }
        var ordered = new LinkedHashMap<Item, Mark>();
        Arrays.stream(Item.values()).forEach(i -> ordered.put(i, Objects.requireNonNull(marks.get(i))));
        return new KitchenVisit(
                id,
                merchantId,
                scheduledAt,
                inspectorId,
                inspectorName,
                passed ? Status.PASSED : Status.FAILED,
                ordered,
                trimmed,
                photoIds,
                scheduledBy,
                by,
                at);
    }

    public KitchenVisit cancel() {
        requireScheduled();
        return new KitchenVisit(
                id,
                merchantId,
                scheduledAt,
                inspectorId,
                inspectorName,
                Status.CANCELLED,
                checklist,
                note,
                photoIds,
                scheduledBy,
                recordedBy,
                recordedAt);
    }

    private void requireScheduled() {
        if (status != Status.SCHEDULED) {
            throw new Conflict("visit_closed", "This visit already has an outcome or was cancelled.");
        }
    }
}
