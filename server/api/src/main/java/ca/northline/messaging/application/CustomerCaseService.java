package ca.northline.messaging.application;

import static ca.northline.messaging.domain.MessagingRules.FILE_MAX_BYTES;
import static ca.northline.messaging.domain.MessagingRules.FILE_REQUIRED;
import static ca.northline.messaging.domain.MessagingRules.FILE_TOO_LARGE;
import static ca.northline.messaging.domain.MessagingRules.FILE_TYPE;
import static ca.northline.messaging.domain.MessagingRules.FILE_TYPES;

import ca.northline.messaging.api.CustomerCaseDesk;
import ca.northline.messaging.application.CustomerCaseStore.NewTicket;
import ca.northline.messaging.application.CustomerCaseStore.StoredUpload;
import ca.northline.messaging.domain.SupportSla;
import ca.northline.messaging.domain.TicketPriority;
import ca.northline.region.api.Regions;
import ca.northline.shared.Bytes;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.storage.ObjectKeys;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Customer cases for Northline's queue (S-60). The case carries the customer's report, the refund cases opened with it
 * and S-132's triage suggestion when there was one ({@code context}); its SLA is the support SLA (urgent 15 min, else
 * 4 h of support hours, the platform zone). Photos use the help form's rules.
 */
@Service
@RequiredArgsConstructor
@Transactional
class CustomerCaseService implements CustomerCaseDesk {

    static final String NOTE_REQUIRED = "Write a message or add a photo.";
    static final String NOTE_TOO_LONG = "Keep messages under 2,000 characters.";
    static final int NOTE_MAX = 2000;
    static final int FILES_MAX = 5;
    static final String TOO_MANY_FILES = "Add up to 5 photos.";
    static final String RESOLVED = "This case is closed. Contact support from Help & cases.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CustomerCaseStore store;
    private final AttachmentStorage storage;
    private final Regions regions;
    private final Clock clock;

    @Override
    public Opened open(NewCase c) {
        var files = ownFiles(c.customerId(), c.attachmentIds());
        var now = clock.instant();
        var priority = c.urgent() ? TicketPriority.URGENT : TicketPriority.NORMAL;
        var due = SupportSla.dueAt(now, priority, regions.platformZone());
        var context = new LinkedHashMap<String, Object>();
        context.put("portal", "consumer");
        context.put("refunds", c.refundIds());
        if (c.category() != null) {
            context.put("triageCategory", c.category());
        }
        if (c.summary() != null) {
            context.put("triageSummary", c.summary());
        }
        var id = Ids.next();
        var subject = c.refLabel() + " · " + c.topic();
        var number = store.insert(new NewTicket(
                id,
                c.customerId(),
                c.topic(),
                subject.length() > 80 ? subject.substring(0, 80) : subject,
                priority,
                c.urgent(),
                due,
                c.refType(),
                c.refId(),
                c.refLabel(),
                "fr".equals(c.locale().getLanguage()) ? "fr" : "en",
                JSON.writeValueAsString(context),
                now));
        var code = "HD-" + number;
        var threadId = Ids.next();
        store.thread(threadId, id, code, subject, now);
        store.message(threadId, "customer", c.customerId(), c.body(), files, now);
        return new Opened(id, code, due);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CaseThread> forRefund(String customerId, String refundId) {
        return store.forRefund(customerId, refundId);
    }

    @Override
    public CaseThread addNote(String customerId, String caseId, String body, List<String> attachmentIds) {
        var threadId = store.threadOf(customerId, caseId).orElseThrow(() -> new NotFound("case", caseId));
        if ("resolved".equals(store.stateOf(caseId))) {
            throw new Conflict("case_resolved", RESOLVED);
        }
        var text = body.strip();
        if (text.isEmpty() && attachmentIds.isEmpty()) {
            throw RuleViolation.of("body", "required", NOTE_REQUIRED);
        }
        if (text.length() > NOTE_MAX) {
            throw RuleViolation.of("body", "length", NOTE_TOO_LONG);
        }
        var now = clock.instant();
        store.message(threadId, "customer", customerId, text, ownFiles(customerId, attachmentIds), now);
        store.touched(caseId, now);
        return store.find(customerId, caseId).orElseThrow();
    }

    @Override
    public Attachment upload(String customerId, String fileName, String contentType, Bytes bytes) {
        if (bytes.isEmpty()) {
            throw RuleViolation.of("file", "required", FILE_REQUIRED);
        }
        var type = contentType.toLowerCase(Locale.ROOT);
        if (!FILE_TYPES.contains(type) || !MessageAttachmentService.signatureMatches(type, bytes.toArray())) {
            throw RuleViolation.of("file", "format", FILE_TYPE);
        }
        if (bytes.size() > FILE_MAX_BYTES) {
            throw RuleViolation.of("file", "size", FILE_TOO_LARGE);
        }
        var id = Ids.next();
        var name = fileName.isBlank() ? id : fileName.strip();
        var upload = new StoredUpload(
                id,
                customerId,
                ObjectKeys.customerObject(customerId, id, type),
                name.length() > 200 ? name.substring(name.length() - 200) : name,
                type,
                bytes.size(),
                clock.instant());
        storage.put(upload.storageKey(), bytes.toArray(), type);
        store.insertUpload(upload);
        return new Attachment(upload.id(), upload.fileName(), upload.contentType(), upload.byteSize());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Content> content(String customerId, String attachmentId) {
        return store.upload(customerId, attachmentId)
                .flatMap(u -> storage.get(u.storageKey()).map(b -> new Content(Bytes.of(b), u.contentType(), u.fileName())));
    }

    private List<String> ownFiles(String customerId, List<String> ids) {
        if (ids.size() > FILES_MAX) {
            throw RuleViolation.of("attachmentIds", "size", TOO_MANY_FILES);
        }
        var own = store.ownUploads(customerId, ids);
        if (own.size() != ids.stream().distinct().count()) {
            throw RuleViolation.of("attachmentIds", "allowed", FILE_REQUIRED);
        }
        return List.copyOf(own);
    }
}
