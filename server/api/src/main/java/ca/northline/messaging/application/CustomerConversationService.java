package ca.northline.messaging.application;

import static ca.northline.messaging.domain.MessagingRules.MESSAGE_MAX;
import static ca.northline.messaging.domain.MessagingRules.MESSAGE_TOO_LONG;

import ca.northline.messaging.api.CustomerConversations;
import ca.northline.messaging.api.MessageSent;
import ca.northline.messaging.api.OffPlatformPhrases;
import ca.northline.messaging.application.ThreadStore.NewMessage;
import ca.northline.messaging.application.ThreadStore.NewThread;
import ca.northline.messaging.domain.OutgoingText;
import ca.northline.messaging.domain.SenderRole;
import ca.northline.messaging.domain.ThreadKind;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-153: the customer's side of the customer threads. Sending masks contact details and detects off-platform payment
 * exactly as the Studio's composer does ({@link OutgoingText}, the S-93 phrases), and publishes {@code message.sent}
 * with {@code senderRole = customer} in the same transaction: trust opens its {@code off_platform_payment} flag on a
 * flagged one, the Studio's live stream refreshes Messages, the worker tells the team.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CustomerConversationService implements CustomerConversations {

    private final ThreadStore threads;
    private final CustomerThreadStore customerThreads;
    private final ApplicationEventPublisher events;
    private final OffPlatformPhrases offPlatform;
    private final Clock clock;

    /** Two sides opening the same thread at once: the insert skips on the unique (merchant, ref) index. */
    @Override
    @Transactional
    public String open(Open command) {
        return threads.findByRef(command.merchantId(), command.refType(), command.refId())
                .orElseGet(() -> {
                    threads.create(new NewThread(
                            Ids.next(),
                            command.merchantId(),
                            ThreadKind.CUSTOMER,
                            command.refType(),
                            command.refId(),
                            command.refCode(),
                            command.customerId(),
                            command.customerName(),
                            command.subject(),
                            command.assigneeId(),
                            clock.instant()));
                    return threads.findByRef(command.merchantId(), command.refType(), command.refId())
                            .orElseThrow();
                });
    }

    @Override
    public List<CustomerThread> threads(String customerId) {
        return customerThreads.list(customerId);
    }

    @Override
    public Optional<ThreadView> thread(String customerId, String threadId) {
        return customerThreads.find(customerId, threadId).map(t -> new ThreadView(t, messages(threadId)));
    }

    @Override
    public Optional<String> threadOf(String merchantId, String refType, String refId) {
        return threads.findByRef(merchantId, refType, refId);
    }

    @Override
    @Transactional
    public Message send(String customerId, String threadId, String body) {
        var thread = own(customerId, threadId);
        var raw = body.strip();
        if (raw.isEmpty()) {
            throw RuleViolation.of("body", "required", MESSAGE_REQUIRED);
        }
        if (raw.length() > MESSAGE_MAX) {
            throw RuleViolation.of("body", "length", MESSAGE_TOO_LONG);
        }
        var text = OutgoingText.of(raw, offPlatform.phrases());
        var now = clock.instant();
        var message = new NewMessage(
                Ids.next(),
                thread.id(),
                SenderRole.CUSTOMER,
                customerId,
                null,
                text.text(),
                List.of(),
                null,
                now,
                text.offPlatform());
        threads.add(message);
        events.publishEvent(new MessageSent(
                Ids.next(),
                now,
                thread.id(),
                message.id(),
                thread.merchantId(),
                ThreadKind.CUSTOMER.code(),
                SenderRole.CUSTOMER.code(),
                customerId,
                text.offPlatform()));
        return new Message(message.id(), "me", message.body(), now, message.flagged());
    }

    @Override
    @Transactional
    public void markRead(String customerId, String threadId) {
        customerThreads.markRead(own(customerId, threadId).id(), clock.instant());
    }

    @Override
    public Optional<String> customerOf(String threadId) {
        return customerThreads.customerOf(threadId);
    }

    private CustomerThread own(String customerId, String threadId) {
        return customerThreads.find(customerId, threadId).orElseThrow(() -> new NotFound("thread", threadId));
    }

    private List<Message> messages(String threadId) {
        return threads.messages(threadId).stream()
                .map(m -> new Message(
                        m.id(),
                        switch (m.senderRole()) {
                            case CUSTOMER -> "me";
                            case MERCHANT -> "business";
                            case AGENT, SYSTEM -> "northline";
                        },
                        m.body(),
                        m.at(),
                        m.flagged()))
                .toList();
    }
}
