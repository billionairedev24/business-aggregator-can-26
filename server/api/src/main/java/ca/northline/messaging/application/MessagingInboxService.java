package ca.northline.messaging.application;

import static ca.northline.messaging.domain.MessagingRules.MESSAGE_MAX;
import static ca.northline.messaging.domain.MessagingRules.MESSAGE_TOO_LONG;

import ca.northline.messaging.api.Conversations;
import ca.northline.messaging.api.MessageSent;
import ca.northline.messaging.application.ThreadStore.NewMessage;
import ca.northline.messaging.application.ThreadStore.NewThread;
import ca.northline.messaging.domain.InboxScope;
import ca.northline.messaging.domain.OutgoingText;
import ca.northline.messaging.domain.Portal;
import ca.northline.messaging.domain.SenderRole;
import ca.northline.messaging.domain.ThreadKind;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Messages: the scoped thread list, one thread with its quick replies, sending (masking + off-platform detection,
 * {@code message.sent} in the same transaction) and the {@link Conversations} API other modules open threads with.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class MessagingInboxService implements BrowseInbox, SendMessage, Conversations {

    private final ThreadStore threads;
    private final AttachmentStore attachments;
    private final MerchantProfiles merchants;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public List<ThreadSummary> threads(String merchantId, Viewer viewer) {
        return threads.list(merchantId, InboxScope.of(viewer.role()), viewer.userId());
    }

    @Override
    public ThreadDetail thread(String merchantId, String threadId, Viewer viewer, Locale locale) {
        var summary = visible(merchantId, threadId, viewer);
        return new ThreadDetail(
                summary, threads.messages(threadId), threads.quickReplies(portal(merchantId), lang(locale)));
    }

    @Override
    @Transactional
    public void markRead(String merchantId, String threadId, Viewer viewer) {
        visible(merchantId, threadId, viewer);
        threads.markRead(threadId, clock.instant());
    }

    @Override
    @Transactional
    public Message send(Command command) {
        var thread = visible(command.merchantId(), command.threadId(), command.viewer());
        var files = MessageDrafts.attachments(attachments, command.merchantId(), command.attachmentIds());
        var raw = MessageDrafts.requireContent(command.body(), files);
        if (raw.length() > MESSAGE_MAX) {
            throw RuleViolation.of("body", "length", MESSAGE_TOO_LONG);
        }
        var text = OutgoingText.of(raw);
        var templateKey = command.templateKey() != null
                        && threads.quickReplyText(portal(command.merchantId()), command.templateKey(), "en")
                                .isPresent()
                ? command.templateKey()
                : null;
        var now = clock.instant();
        var message = new NewMessage(
                Ids.next(),
                thread.id(),
                SenderRole.MERCHANT,
                command.viewer().userId(),
                null,
                text.text(),
                files,
                templateKey,
                now,
                text.offPlatform());
        threads.add(message);
        events.publishEvent(new MessageSent(
                Ids.next(),
                now,
                thread.id(),
                message.id(),
                command.merchantId(),
                thread.kind().code(),
                SenderRole.MERCHANT.code(),
                command.viewer().userId(),
                text.offPlatform()));
        return MessageDrafts.view(message, attachments, command.merchantId());
    }

    @Override
    @Transactional
    public String open(Open command) {
        return threads.findByRef(command.merchantId(), command.refType(), command.refId())
                .orElseGet(() -> {
                    var id = Ids.next();
                    threads.create(new NewThread(
                            id,
                            command.merchantId(),
                            CodedEnum.fromCode(ThreadKind.class, command.kind()),
                            command.refType(),
                            command.refId(),
                            command.refCode(),
                            command.counterpartId(),
                            command.counterpartName(),
                            command.subject(),
                            command.assigneeId(),
                            clock.instant()));
                    return id;
                });
    }

    private ThreadSummary visible(String merchantId, String threadId, Viewer viewer) {
        return threads.find(merchantId, threadId, InboxScope.of(viewer.role()), viewer.userId())
                .orElseThrow(() -> new NotFound("thread", threadId));
    }

    private Portal portal(String merchantId) {
        return merchants
                .profile(merchantId)
                .map(p -> Portal.ofMerchantType(p.type()))
                .orElseThrow(() -> new NotFound("merchant", merchantId));
    }

    static String lang(Locale locale) {
        return "fr".equals(locale.getLanguage()) ? "fr" : "en";
    }
}
