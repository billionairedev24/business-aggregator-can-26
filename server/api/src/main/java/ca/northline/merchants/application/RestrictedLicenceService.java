package ca.northline.merchants.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.RestrictedLicenceChanged;
import ca.northline.merchants.api.RestrictedLicenceUpdated;
import ca.northline.merchants.api.RestrictedLicences;
import ca.northline.merchants.application.Documents.ReadDocument;
import ca.northline.merchants.application.Documents.UploadDocument;
import ca.northline.merchants.application.RestrictedLicenceUseCases.Decision;
import ca.northline.merchants.application.RestrictedLicenceUseCases.LicenceJobs;
import ca.northline.merchants.application.RestrictedLicenceUseCases.LicenceQueue;
import ca.northline.merchants.application.RestrictedLicenceUseCases.QueueItem;
import ca.northline.merchants.application.RestrictedLicenceUseCases.SubmitLicence;
import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.LicenceRules;
import ca.northline.region.api.AgeClass;
import ca.northline.region.api.Regions;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Licences for age-restriction classes. Submitted by an owner (document through the storage port), approved or
 * rejected by trust &amp; safety in the console's vetting queue, expired by the nightly job; every step is audited
 * ({@code merchants.restricted_licence_*}, ids and codes only). {@link RestrictedLicenceChanged} tells the catalogue
 * and food modules when the business may (or may no longer) sell a class. "Today" is the platform zone's date.
 */
@Service
@RequiredArgsConstructor
@Transactional
class RestrictedLicenceService implements RestrictedLicences, SubmitLicence, LicenceQueue, LicenceJobs {

    static final int BATCH = 200;

    private final RestrictedLicenceStore store;
    private final UploadDocument upload;
    private final ReadDocument read;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Regions regions;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Set<AgeClass> licensedClasses(String merchantId) {
        return store.licensedClasses(merchantId, today());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Licence> of(String merchantId) {
        return store.of(merchantId);
    }

    // ── Studio ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Licence submit(Command c) {
        var violations = new ArrayList<Violation>();
        var ageClass = AgeClass.of(c.ageClass()).orElse(null);
        if (ageClass == null) {
            violations.add(new Violation("ageClass", "required", LicenceRules.CLASS));
        }
        var number = c.licenceNumber() == null ? "" : c.licenceNumber().strip();
        if (!LicenceRules.validNumber(number)) {
            violations.add(new Violation("licenceNumber", "length", LicenceRules.NUMBER));
        }
        var expires = c.expiresOn();
        if (expires == null || !LicenceRules.validExpiry(expires, today())) {
            violations.add(new Violation("expiresOn", "range", LicenceRules.EXPIRY));
        }
        var bytes = c.bytes();
        if (bytes == null || bytes.isEmpty()) {
            violations.add(new Violation(Documents.FIELD, "required", LicenceRules.DOCUMENT));
        }
        if (!violations.isEmpty()) {
            throw new RuleViolation(violations);
        }
        var province = store.province(c.merchantId());
        if (province == null || regions.province(province).isEmpty()) {
            throw RuleViolation.of("ageClass", "province", LicenceRules.NO_PROVINCE);
        }
        Document document;
        try {
            document = upload.upload(new UploadDocument.Command(
                    c.merchantId(),
                    c.actorId(),
                    Document.Purpose.VERIFICATION,
                    Objects.requireNonNullElse(c.fileName(), "licence"),
                    Objects.requireNonNullElse(c.contentType(), "application/octet-stream"),
                    Objects.requireNonNull(bytes)));
        } catch (RuleViolation e) {
            throw RuleViolation.of(Documents.FIELD, "type", LicenceRules.DOCUMENT);
        }
        var now = clock.instant();
        var licence = new Licence(
                Ids.next(),
                c.merchantId(),
                Objects.requireNonNull(ageClass),
                province,
                number,
                document.id(),
                Objects.requireNonNull(expires),
                "pending",
                now,
                null,
                null,
                null);
        store.insert(licence, c.actorId());
        audit.record(new AuditTrail.Entry(
                c.merchantId(),
                c.actorId(),
                c.actorRole(),
                "merchants.restricted_licence_submitted",
                "restricted_licence",
                licence.id(),
                null,
                Map.of(
                        "ageClass",
                        licence.ageClass().code(),
                        "expiresOn",
                        licence.expiresOn().toString())));
        return licence;
    }

    // ── Console ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<QueueItem> queue(MerchantScope scope, @Nullable String status) {
        var s = status == null || status.isBlank() ? "pending" : status;
        return store.queue(scope, s, BATCH).stream()
                .map(r -> new QueueItem(r.licence(), r.businessName(), r.businessProvince()))
                .toList();
    }

    @Override
    public QueueItem decide(Decision d) {
        var reason = d.reason();
        if (!d.approve() && (reason == null || !LicenceRules.REJECT_REASONS.contains(reason))) {
            throw RuleViolation.of("reason", "required", LicenceRules.REASON);
        }
        var licence = store.lock(d.licenceId()).orElseThrow(() -> new NotFound("licence", d.licenceId()));
        if (!"pending".equals(licence.status())) {
            throw new Conflict("licence_decided", LicenceRules.NOT_PENDING);
        }
        var now = clock.instant();
        var was = licensedClasses(licence.merchantId()).contains(licence.ageClass());
        var status = d.approve() ? "approved" : "rejected";
        store.decide(licence.id(), status, d.staffId(), now, d.approve() ? null : reason, d.note());
        if (d.approve()) {
            store.replaceOthers(licence.merchantId(), licence.ageClass(), licence.id());
        }
        audit.record(new AuditTrail.Entry(
                licence.merchantId(),
                d.staffId(),
                d.staffRoles(),
                "merchants.restricted_licence_" + status,
                "restricted_licence",
                licence.id(),
                Map.of("status", "pending"),
                d.approve()
                        ? Map.of(
                                "status", status, "ageClass", licence.ageClass().code())
                        : Map.of("status", status, "reason", Objects.requireNonNull(reason))));
        events.publishEvent(new RestrictedLicenceUpdated(
                Ids.next(),
                now,
                licence.id(),
                licence.merchantId(),
                status,
                licence.ageClass().code(),
                licence.expiresOn(),
                d.approve() ? null : reason,
                d.note()));
        var now2 = licensedClasses(licence.merchantId()).contains(licence.ageClass());
        if (now2 != was) {
            events.publishEvent(new RestrictedLicenceChanged(
                    Ids.next(), now, licence.merchantId(), licence.ageClass().code(), now2));
        }
        var decided = store.row(licence.id()).orElseThrow();
        return new QueueItem(decided.licence(), decided.businessName(), decided.businessProvince());
    }

    @Override
    @Transactional(readOnly = true)
    public ReadDocument.Content document(String licenceId) {
        var licence = store.row(licenceId)
                .orElseThrow(() -> new NotFound("licence", licenceId))
                .licence();
        return read.read(licence.merchantId(), licence.documentId());
    }

    // ── Jobs ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public int expireDue() {
        var today = today();
        var due = store.dueToExpire(today, BATCH);
        var now = clock.instant();
        for (var licence : due) {
            store.expire(licence.id());
            audit.record(new AuditTrail.Entry(
                    licence.merchantId(),
                    "system",
                    "system",
                    "merchants.restricted_licence_expired",
                    "restricted_licence",
                    licence.id(),
                    Map.of("status", "approved"),
                    Map.of("status", "expired", "expiresOn", licence.expiresOn().toString())));
            events.publishEvent(new RestrictedLicenceUpdated(
                    Ids.next(),
                    now,
                    licence.id(),
                    licence.merchantId(),
                    "expired",
                    licence.ageClass().code(),
                    licence.expiresOn(),
                    null,
                    null));
            if (!store.licensedClasses(licence.merchantId(), today).contains(licence.ageClass())) {
                events.publishEvent(new RestrictedLicenceChanged(
                        Ids.next(),
                        now,
                        licence.merchantId(),
                        licence.ageClass().code(),
                        false));
            }
        }
        return due.size();
    }

    @Override
    public int remindDue() {
        var due = store.dueForReminder(today().plusDays(LicenceRules.REMIND_DAYS), BATCH);
        var now = clock.instant();
        for (var licence : due) {
            store.reminded(licence.id(), now);
            events.publishEvent(new RestrictedLicenceUpdated(
                    Ids.next(),
                    now,
                    licence.id(),
                    licence.merchantId(),
                    "expiring",
                    licence.ageClass().code(),
                    licence.expiresOn(),
                    null,
                    null));
        }
        return due.size();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), regions.platformZone());
    }
}
