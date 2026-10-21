package ca.northline.standing.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.messaging.api.StandingNotice;
import ca.northline.payments.api.DisputeAppeals;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.storage.ObjectKeys;
import ca.northline.standing.application.StandingUseCases.AppealDesk;
import ca.northline.standing.application.StandingUseCases.AppealDetail;
import ca.northline.standing.application.StandingUseCases.AppealView;
import ca.northline.standing.application.StandingUseCases.FileAppeal;
import ca.northline.standing.application.StandingUseCases.QueueItem;
import ca.northline.standing.application.StandingUseCases.StoredFile;
import ca.northline.standing.application.StandingUseCases.Upload;
import ca.northline.standing.domain.Appeal;
import ca.northline.standing.domain.AppealRules;
import ca.northline.standing.domain.Consequence;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appeals (S-156): the business files once per decision or consequence within 14 days ({@link AppealRules}), staff take
 * and decide them in the console. Granting a consequence's appeal lifts it (the owning module switches back); granting
 * a dispute's makes Northline pay the seller what the decision cost them. Every step is audited; the decision is
 * emailed to the owners.
 */
@Service
@RequiredArgsConstructor
@Transactional
class AppealService implements FileAppeal, AppealDesk {

    static final int LIMIT = 200;
    static final Duration DECIDED_SHOWN = Duration.ofDays(7);

    private final AppealStore appeals;
    private final ConsequenceStore consequences;
    private final ConsequenceService registry;
    private final DisputeAppeals disputes;
    private final AppealFiles files;
    private final BusinessNames names;
    private final MerchantPlaces places;
    private final PersonDirectory people;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ── Studio ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public AppealView file(String merchantId, String subjectType, String subjectId, String statement, String userId) {
        var now = clock.instant();
        var subject = switch (subjectType) {
            case "dispute" -> Appeal.Subject.DISPUTE;
            case "consequence" -> Appeal.Subject.CONSEQUENCE;
            default -> throw RuleViolation.of("subjectType", "required", AppealRules.SUBJECT_REQUIRED);
        };
        var text = AppealRules.statement(statement);
        if (appeals.forSubject(subject, subjectId).isPresent()) {
            throw new Conflict("already_appealed", AppealRules.ALREADY_APPEALED);
        }
        String kind;
        String original;
        if (subject == Appeal.Subject.DISPUTE) {
            var facts = disputes.facts(merchantId, subjectId).orElseThrow(() -> new NotFound("dispute", subjectId));
            if (!facts.appealable(now)) {
                var late = facts.agentDecided()
                        && facts.decidedAt() != null
                        && facts.refundCents() > 0
                        && now.isAfter(facts.decidedAt().plus(DisputeAppeals.WINDOW));
                throw late
                        ? new Conflict("appeal_window_closed", AppealRules.WINDOW_CLOSED)
                        : new Conflict("not_appealable", AppealRules.NOT_APPEALABLE);
            }
            kind = Objects.requireNonNull(facts.decision());
            original = facts.decidedBy();
        } else {
            var c = consequences
                    .find(subjectId)
                    .filter(x -> merchantId.equals(x.merchantId()))
                    .orElseThrow(() -> new NotFound("consequence", subjectId));
            if (!c.kind().appealable()) {
                throw new Conflict("not_appealable", AppealRules.NOT_APPEALABLE);
            }
            if (!c.active()) {
                throw new Conflict("consequence_lifted", AppealRules.LIFTED);
            }
            if (!c.appealableAt(now)) {
                throw new Conflict("appeal_window_closed", AppealRules.WINDOW_CLOSED);
            }
            kind = c.kind().code();
            original = c.appliedBy();
        }
        var appeal = Appeal.file(
                Ids.next(), appeals.nextNumber(), merchantId, subject, subjectId, kind, original, text, userId, now);
        if (!appeals.insert(appeal)) {
            throw new Conflict("already_appealed", AppealRules.ALREADY_APPEALED);
        }
        audit.record(new AuditTrail.Entry(
                merchantId,
                userId,
                "owner",
                "standing.appeal_filed",
                "appeal",
                appeal.id(),
                null,
                Map.of("subjectType", subject.code(), "subjectId", subjectId, "subjectKind", kind)));
        return view(appeal);
    }

    @Override
    public AppealView attach(String merchantId, String appealId, Upload upload) {
        var appeal = appeals.lock(appealId)
                .filter(a -> a.merchantId().equals(merchantId))
                .orElseThrow(() -> new NotFound("appeal", appealId));
        if (upload.bytes().isEmpty()) {
            throw RuleViolation.of("file", "required", AppealRules.FILE_REQUIRED);
        }
        var type = AppealRules.fileType(upload.contentType())
                .orElseThrow(() -> RuleViolation.of("file", "type", AppealRules.FILE_TYPE));
        if (upload.bytes().size() > AppealRules.FILE_MAX_BYTES) {
            throw RuleViolation.of("file", "size", AppealRules.FILE_SIZE);
        }
        var id = Ids.next();
        var file = new Appeal.AppealFile(
                id,
                clipName(upload.name()),
                type,
                upload.bytes().size(),
                ObjectKeys.merchantObject(merchantId, id, type),
                clock.instant());
        var updated = appeal.attach(file);
        files.put(file.storageKey(), upload.bytes(), type);
        appeals.insertFile(appealId, file);
        return view(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFile> file(@Nullable String merchantId, String appealId, String fileId) {
        return appeals.find(appealId)
                .filter(a -> merchantId == null || a.merchantId().equals(merchantId))
                .flatMap(a ->
                        a.files().stream().filter(f -> f.id().equals(fileId)).findFirst())
                .flatMap(f -> files.get(f.storageKey()).map(b -> new StoredFile(b, f.contentType(), f.name())));
    }

    // ── Console ───────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<QueueItem> queue(MerchantScope scope) {
        var rows = appeals.queue(scope, clock.instant().minus(DECIDED_SHOWN), LIMIT);
        var who = names(rows.stream().map(Appeal::assigneeId));
        return rows.stream().map(a -> item(a, who)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppealDetail> detail(String appealId) {
        return appeals.find(appealId).map(this::detail);
    }

    @Override
    public AppealDetail assign(String appealId, String staffId, String role) {
        var appeal = locked(appealId).assign(staffId, clock.instant());
        appeals.update(appeal);
        audit.record(new AuditTrail.Entry(
                appeal.merchantId(), staffId, role, "standing.appeal_assigned", "appeal", appealId, null, null));
        return detail(appeal);
    }

    @Override
    public AppealDetail decide(String appealId, boolean grant, String reason, String staffId, String role) {
        var now = clock.instant();
        var appeal = locked(appealId).decide(grant, reason, staffId, role, now);
        var why = Objects.requireNonNull(appeal.decisionReason());
        @Nullable String reference = appeal.number();
        if (grant) {
            if (appeal.subjectType() == Appeal.Subject.DISPUTE) {
                appeal = appeal.withCompensationCents(disputes.compensate(appeal.id(), appeal.subjectId(), staffId));
                reference = disputes.facts(appeal.merchantId(), appeal.subjectId())
                        .map(DisputeAppeals.Facts::caseNumber)
                        .orElse(appeal.number());
            } else if (consequences
                    .find(appeal.subjectId())
                    .map(Consequence::active)
                    .orElse(false)) {
                registry.lift(
                        appeal.subjectId(),
                        "Appeal %s granted: %s".formatted(appeal.number(), why),
                        staffId,
                        role,
                        appeal.id());
            }
        }
        appeals.update(appeal);
        audit.record(new AuditTrail.Entry(
                appeal.merchantId(),
                staffId,
                role,
                grant ? "standing.appeal_granted" : "standing.appeal_denied",
                "appeal",
                appealId,
                null,
                Map.of(
                        "subjectType", appeal.subjectType().code(),
                        "subjectId", appeal.subjectId(),
                        "compensationCents", appeal.compensationCents())));
        events.publishEvent(new StandingNotice(
                Ids.next(),
                now,
                appeal.id(),
                appeal.merchantId(),
                grant ? "appeal_granted" : "appeal_denied",
                appeal.subjectType() == Appeal.Subject.DISPUTE ? "dispute" : appeal.subjectKind(),
                why,
                reference,
                appeal.compensationCents()));
        return detail(appeal);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────

    private Appeal locked(String appealId) {
        return appeals.lock(appealId).orElseThrow(() -> new NotFound("appeal", appealId));
    }

    private AppealDetail detail(Appeal a) {
        var who = names(Stream.of(a.assigneeId(), a.decidedBy(), a.originalActor()));
        var item = item(a, who);
        StandingUseCases.ConsequenceView consequence = null;
        StandingUseCases.DisputeView dispute = null;
        if (a.subjectType() == Appeal.Subject.CONSEQUENCE) {
            consequence = consequences
                    .find(a.subjectId())
                    .map(c -> StandingViewService.view(c, null, clock.instant()))
                    .orElse(null);
        } else {
            dispute = disputes.facts(a.merchantId(), a.subjectId())
                    .filter(f -> f.decidedAt() != null && f.decision() != null)
                    .map(StandingViewService::dispute)
                    .orElse(null);
        }
        return new AppealDetail(
                item,
                consequence,
                dispute,
                a.originalActor(),
                a.originalActor() == null ? null : who.get(a.originalActor()),
                a.decidedBy(),
                a.decidedBy() == null ? null : who.get(a.decidedBy()));
    }

    private QueueItem item(Appeal a, Map<String, String> who) {
        var place = places.of(a.merchantId());
        return new QueueItem(
                view(a),
                a.merchantId(),
                names.displayName(a.merchantId()).orElse(a.merchantId()),
                place.ownProvince() ? place.province() : null,
                a.assigneeId(),
                a.assigneeId() == null ? null : who.get(a.assigneeId()));
    }

    /** Display names of people by id; {@code system} is the trust rules. */
    private Map<String, String> names(Stream<@Nullable String> ids) {
        var wanted = new HashSet<String>();
        ids.filter(Objects::nonNull).forEach(wanted::add);
        var out = new java.util.HashMap<String, String>();
        if (wanted.remove(Consequence.SYSTEM)) {
            out.put(Consequence.SYSTEM, "Northline's trust rules");
        }
        people.people(wanted).forEach((id, p) -> out.put(id, p.displayName()));
        return out;
    }

    AppealView view(Appeal a) {
        @Nullable String label = null;
        if (a.subjectType() == Appeal.Subject.DISPUTE) {
            label = disputes.facts(a.merchantId(), a.subjectId())
                    .map(DisputeAppeals.Facts::caseNumber)
                    .orElse(null);
        }
        return StandingViewService.view(a, label);
    }

    private static String clipName(String name) {
        var n = name.strip().replaceAll("[\\p{Cntrl}/\\\\]", "_");
        if (n.isEmpty()) {
            return "evidence";
        }
        return n.length() > 200 ? n.substring(0, 200) : n;
    }
}
