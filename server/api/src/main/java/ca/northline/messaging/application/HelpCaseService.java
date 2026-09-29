package ca.northline.messaging.application;

import static ca.northline.messaging.application.MessagingInboxService.lang;
import static ca.northline.messaging.domain.MessagingRules.CASE_BODY_MAX;
import static ca.northline.messaging.domain.MessagingRules.CASE_BODY_REQUIRED;
import static ca.northline.messaging.domain.MessagingRules.CASE_BODY_TOO_LONG;
import static ca.northline.messaging.domain.MessagingRules.RELATED_INVALID;
import static ca.northline.messaging.domain.MessagingRules.SUBJECT_MAX;

import ca.northline.messaging.api.CaseReferences;
import ca.northline.messaging.api.CaseReferences.Reference;
import ca.northline.messaging.api.MessageSent;
import ca.northline.messaging.api.TicketOpened;
import ca.northline.messaging.application.BrowseInbox.Message;
import ca.northline.messaging.application.HelpCaseStore.Context;
import ca.northline.messaging.application.HelpCaseStore.NewCase;
import ca.northline.messaging.application.ThreadStore.NewMessage;
import ca.northline.messaging.application.ThreadStore.NewThread;
import ca.northline.messaging.domain.SenderRole;
import ca.northline.messaging.domain.SupportCase;
import ca.northline.messaging.domain.SupportSla;
import ca.northline.messaging.domain.ThreadKind;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Helpdesk cases opened from Help › Contact support. The SLA comes from the tier and the urgent flag; the account
 * context (portal, tier, role, last 5 events) is attached automatically; the case conversation is a
 * {@code messaging.threads} row of kind {@code case}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class HelpCaseService implements ManageHelpCases {

    static final String NORTHLINE_SUPPORT = "Northline support";

    private final HelpCaseStore cases;
    private final ThreadStore threads;
    private final AttachmentStore attachments;
    private final MerchantProfiles merchants;
    private final List<CaseReferences> references;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public List<CaseSummary> cases(String merchantId) {
        return cases.list(merchantId);
    }

    @Override
    @Transactional
    public CaseSummary open(Open command) {
        var profile = merchants
                .profile(command.merchantId())
                .orElseThrow(() -> new NotFound("merchant", command.merchantId()));
        if (command.refType() != null
                && (command.refId() == null || command.refId().isBlank())) {
            throw RuleViolation.of("refId", "required", RELATED_INVALID);
        }
        var files = MessageDrafts.attachments(attachments, command.merchantId(), command.attachmentIds());
        var body = command.body().strip();
        if (body.isEmpty()) {
            throw RuleViolation.of("body", "required", CASE_BODY_REQUIRED);
        }
        if (body.length() > CASE_BODY_MAX) {
            throw RuleViolation.of("body", "length", CASE_BODY_TOO_LONG);
        }
        var now = clock.instant();
        var id = Ids.next();
        var priority = SupportSla.priority(command.urgent(), profile.masterTier());
        var opened = SupportCase.open(id, 0, priority, now);
        var subject = subjectOf(body);
        var context = new Context(
                profile.type(), profile.tier(), command.role().code(), cases.recentEvents(command.merchantId(), 5));
        int number = cases.insert(new NewCase(
                id,
                command.merchantId(),
                command.userId(),
                command.topic(),
                subject,
                priority,
                command.urgent(),
                command.channel(),
                requireDue(opened.slaDueAt()),
                command.refType(),
                command.refType() == null ? null : command.refId(),
                command.refType() == null ? null : command.refLabel(),
                lang(command.locale()),
                context,
                now));
        var threadId = Ids.next();
        threads.create(new NewThread(
                threadId,
                command.merchantId(),
                ThreadKind.CASE,
                "ticket",
                id,
                SupportCase.code(number),
                null,
                NORTHLINE_SUPPORT,
                subject,
                null,
                now));
        threads.add(new NewMessage(
                Ids.next(), threadId, SenderRole.MERCHANT, command.userId(), null, body, files, null, now, false));
        events.publishEvent(new TicketOpened(
                Ids.next(),
                now,
                id,
                command.merchantId(),
                command.topic(),
                priority.code(),
                command.channel(),
                requireDue(opened.slaDueAt())));
        return cases.find(command.merchantId(), id).orElseThrow();
    }

    @Override
    public CaseDetail view(String merchantId, String caseId) {
        var summary = cases.find(merchantId, caseId).orElseThrow(() -> new NotFound("case", caseId));
        var messages = threads.findByRef(merchantId, "ticket", caseId)
                .map(threads::messages)
                .orElse(List.of());
        return new CaseDetail(summary, messages);
    }

    @Override
    @Transactional
    public Message reply(Reply command) {
        var current = cases.state(command.merchantId(), command.caseId())
                .orElseThrow(() -> new NotFound("case", command.caseId()));
        var files = MessageDrafts.attachments(attachments, command.merchantId(), command.attachmentIds());
        var body = MessageDrafts.requireContent(command.body(), files);
        if (body.length() > CASE_BODY_MAX) {
            throw RuleViolation.of("body", "length", CASE_BODY_TOO_LONG);
        }
        var now = clock.instant();
        var progressed = current.replied(now);
        cases.update(progressed, now);
        var threadId = threads.findByRef(command.merchantId(), "ticket", command.caseId())
                .orElseThrow(() -> new NotFound("case thread", command.caseId()));
        var message = new NewMessage(
                Ids.next(), threadId, SenderRole.MERCHANT, command.userId(), null, body, files, null, now, false);
        threads.add(message);
        events.publishEvent(new MessageSent(
                Ids.next(),
                now,
                threadId,
                message.id(),
                command.merchantId(),
                ThreadKind.CASE.code(),
                SenderRole.MERCHANT.code(),
                command.userId(),
                false));
        return MessageDrafts.view(message, attachments, command.merchantId());
    }

    @Override
    public List<Reference> related(String merchantId, Locale locale) {
        var unique = new LinkedHashMap<String, Reference>();
        references.stream()
                .flatMap(r -> r.recent(merchantId, locale).stream())
                .forEach(r -> unique.putIfAbsent(r.type() + ":" + r.id(), r));
        return List.copyOf(unique.values());
    }

    /** First line of the description, cut at {@value ca.northline.messaging.domain.MessagingRules#SUBJECT_MAX}. */
    static String subjectOf(String body) {
        var line = body.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .findFirst()
                .orElse(body);
        return line.length() <= SUBJECT_MAX
                ? line
                : line.substring(0, SUBJECT_MAX - 1).stripTrailing() + "…";
    }

    private static Instant requireDue(@Nullable Instant due) {
        if (due == null) {
            throw new IllegalStateException("a new case always has an SLA");
        }
        return due;
    }
}
