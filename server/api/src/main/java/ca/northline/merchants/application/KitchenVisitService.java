package ca.northline.merchants.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.KitchenVisits;
import ca.northline.merchants.api.PilotCohort;
import ca.northline.merchants.application.Documents.ReadDocument;
import ca.northline.merchants.application.Documents.UploadDocument;
import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.KitchenVisit;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.SelectedCategory;
import ca.northline.merchants.domain.Verification;
import ca.northline.merchants.domain.VerificationStatus;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.PlatformRoles;
import ca.northline.shared.security.StaffRole;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link KitchenVisits}: schedules visits, keeps their photos as verification documents (the merchants storage port),
 * records outcomes and moves the kitchen's {@code site_visit} check with them. Audit-logged on the business.
 */
@Service
@RequiredArgsConstructor
@Transactional
class KitchenVisitService implements KitchenVisits {

    private static final Duration HORIZON = Duration.ofDays(60);
    private static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png");
    static final String CHECKLIST_CODES = "Mark each item pass, fail or n/a.";

    private final KitchenVisitStore visits;
    private final ApplicationRepository applications;
    private final VerificationRepository verifications;
    private final UploadDocument upload;
    private final ReadDocument read;
    private final KitchenVisitPolicy policy;
    private final PlatformRoles platformRoles;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    public List<String> items() {
        return Arrays.stream(KitchenVisit.Item.values())
                .map(KitchenVisit.Item::code)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Visit> visits(String merchantId) {
        return visits.visits(merchantId).stream().map(KitchenVisitService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean required(String merchantId) {
        var application = kitchen(merchantId);
        return policy.required(
                merchantId,
                application.getType(),
                application.getProvince() == null
                        ? null
                        : application.getProvince().code(),
                categoryIds(application));
    }

    @Override
    public Visit schedule(Schedule command, PilotCohort.Actor actor) {
        kitchen(command.merchantId());
        var now = clock.instant();
        var at = command.at();
        if (at == null || !at.isAfter(now) || at.isAfter(now.plus(HORIZON))) {
            throw RuleViolation.of("at", "range", WHEN_REQUIRED);
        }
        var inspectorId = blankToNull(command.inspectorId());
        var inspectorName = blankToNull(command.inspectorName());
        if (inspectorId == null && inspectorName == null) {
            throw RuleViolation.of("inspectorId", "required", INSPECTOR_REQUIRED);
        }
        if (inspectorId != null && !platformRoles.of(inspectorId).contains(StaffRole.STAFF)) {
            throw RuleViolation.of("inspectorId", "staff", PilotCohort.OWNER_NOT_STAFF);
        }
        if (inspectorName != null && inspectorName.length() > 80) {
            throw RuleViolation.of("inspectorName", "length", INSPECTOR_NAME_LENGTH);
        }
        var visit =
                KitchenVisit.schedule(Ids.next(), command.merchantId(), at, inspectorId, inspectorName, actor.userId());
        visits.insert(visit);
        // the owner's checklist shows the visit as booked (as if they had picked this slot themselves)
        siteVisit(command.merchantId())
                .filter(v -> v.getStatus() == VerificationStatus.TODO || v.getStatus() == VerificationStatus.REJECTED)
                .ifPresent(v -> {
                    v.submit(at.toString(), null, null, now);
                    verifications.save(v);
                });
        record(actor, command.merchantId(), "kitchen_visit.scheduled", visit.id(), Map.of("at", at.toString()));
        return view(visit);
    }

    @Override
    public Visit addPhoto(String merchantId, String visitId, Upload photo, PilotCohort.Actor actor) {
        var visit = lock(merchantId, visitId);
        if (!PHOTO_TYPES.contains(photo.contentType()) || photo.bytes().size() > Documents.MAX_BYTES) {
            throw RuleViolation.of(Documents.FIELD, "type", PHOTO_TYPE);
        }
        var document = upload.upload(new UploadDocument.Command(
                merchantId,
                actor.userId(),
                Document.Purpose.VERIFICATION,
                photo.fileName(),
                photo.contentType(),
                photo.bytes()));
        var updated = visit.withPhoto(document.id());
        visits.update(updated);
        record(actor, merchantId, "kitchen_visit.photo_added", visitId, Map.of("documentId", document.id()));
        return view(updated);
    }

    @Override
    public Visit record(
            String merchantId,
            String visitId,
            boolean passed,
            Map<String, String> checklist,
            @Nullable String note,
            PilotCohort.Actor actor) {
        var visit = lock(merchantId, visitId);
        if (note != null && note.length() > KitchenVisit.NOTE_MAX) {
            throw RuleViolation.of("note", "length", KitchenVisit.NOTE_TOO_LONG);
        }
        var marks = new EnumMap<KitchenVisit.Item, KitchenVisit.Mark>(KitchenVisit.Item.class);
        checklist.forEach((item, mark) -> {
            try {
                marks.put(
                        CodedEnum.fromCode(KitchenVisit.Item.class, item),
                        CodedEnum.fromCode(KitchenVisit.Mark.class, mark));
            } catch (IllegalArgumentException _) {
                throw RuleViolation.of("checklist", "format", CHECKLIST_CODES);
            }
        });
        var now = clock.instant();
        var recorded = visit.record(passed, marks, note, actor.userId(), now);
        visits.update(recorded);
        siteVisit(merchantId).ifPresent(v -> {
            if (passed) {
                v.follow(VerificationStatus.VERIFIED, "visit:" + visitId, now);
            } else {
                v.reopen(now);
            }
            verifications.save(v);
        });
        record(
                actor,
                merchantId,
                passed ? "kitchen_visit.passed" : "kitchen_visit.failed",
                visitId,
                Map.of(
                        "failed",
                        marks.entrySet().stream()
                                .filter(e -> e.getValue() == KitchenVisit.Mark.FAIL)
                                .map(e -> e.getKey().code())
                                .toList()));
        return view(recorded);
    }

    @Override
    public Visit cancel(String merchantId, String visitId, PilotCohort.Actor actor) {
        var cancelled = lock(merchantId, visitId).cancel();
        visits.update(cancelled);
        record(actor, merchantId, "kitchen_visit.cancelled", visitId, Map.of());
        return view(cancelled);
    }

    @Override
    @Transactional(readOnly = true)
    public Upload photo(String merchantId, String visitId, String photoId) {
        var visit = visits.visits(merchantId).stream()
                .filter(v -> v.id().equals(visitId))
                .findFirst()
                .orElseThrow(() -> new NotFound("kitchen visit", visitId));
        if (!visit.photoIds().contains(photoId)) {
            throw new NotFound("photo", photoId);
        }
        var content = read.read(merchantId, photoId);
        return new Upload(content.document().fileName(), content.document().contentType(), content.bytes());
    }

    private MerchantApplication kitchen(String merchantId) {
        var application = applications.findById(merchantId).orElseThrow(() -> new NotFound("business", merchantId));
        if (application.getType() != MerchantType.KITCHEN) {
            throw new Conflict("not_a_kitchen", NOT_A_KITCHEN);
        }
        return application;
    }

    private KitchenVisit lock(String merchantId, String visitId) {
        return visits.lock(merchantId, visitId).orElseThrow(() -> new NotFound("kitchen visit", visitId));
    }

    private java.util.Optional<Verification> siteVisit(String merchantId) {
        return verifications.listFor(merchantId).stream()
                .filter(v -> v.kind() == CheckKind.SITE_VISIT)
                .findFirst();
    }

    private void record(
            PilotCohort.Actor actor, String merchantId, String action, String visitId, Map<String, ?> after) {
        audit.record(new AuditTrail.Entry(
                merchantId, actor.userId(), actor.role(), action, "kitchen_visit", visitId, null, after));
    }

    static List<String> categoryIds(MerchantApplication application) {
        return application.getCategories().stream()
                .filter(c -> !c.suggested())
                .map(SelectedCategory::id)
                .toList();
    }

    static Visit view(KitchenVisit v) {
        var checklist = new HashMap<String, String>();
        v.checklist().forEach((item, mark) -> checklist.put(item.code(), mark.code()));
        return new Visit(
                v.id(),
                v.merchantId(),
                v.scheduledAt(),
                v.inspectorId(),
                v.inspectorName(),
                v.status().code(),
                checklist,
                v.note(),
                v.photoIds(),
                v.recordedBy(),
                v.recordedAt());
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
