package ca.northline.hire.application;

import ca.northline.booking.api.CustomerBookings;
import ca.northline.booking.api.CustomerQuotes;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.PublicProviders;
import ca.northline.messaging.api.CustomerConversations;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * S-153: composes the customer's threads ({@code messaging.api}) with the jobs they are about ({@code booking.api}) and
 * the providers' public names. The thread is opened with the customer's short name ("Amara O.") — the Studio's
 * counterpart — and, for a booking, the member doing the job as assignee (technicians see only their own threads).
 */
@Service
@RequiredArgsConstructor
class CustomerMessageService implements CustomerMessages {

    static final String QUOTE = "quote";
    static final String BOOKING = "booking";

    private final CustomerConversations conversations;
    private final CustomerQuotes quotes;
    private final CustomerBookings bookings;
    private final PublicProviders providers;
    private final PersonDirectory people;

    @Override
    public List<ThreadRow> threads(String customerId) {
        var threads = conversations.threads(customerId);
        var names = names(threads.stream()
                .map(CustomerConversations.CustomerThread::merchantId)
                .toList());
        return threads.stream().map(t -> row(t, names)).toList();
    }

    @Override
    public ThreadPage thread(String customerId, String threadId) {
        var view = conversations.thread(customerId, threadId).orElseThrow(() -> new NotFound("thread", threadId));
        return new ThreadPage(row(view.thread(), names(List.of(view.thread().merchantId()))), view.messages());
    }

    @Override
    public ThreadPage open(String customerId, String refType, String refId, @Nullable String merchantId) {
        var customer = people.people(List.of(customerId)).get(customerId);
        var name = customer == null ? "Customer" : customer.shortName();
        var open = switch (refType) {
            case QUOTE -> {
                var request = quotes.request(customerId, refId).orElseThrow(() -> new NotFound("quote request", refId));
                if (merchantId == null || !request.merchantIds().contains(merchantId)) {
                    throw RuleViolation.of("merchantId", "allowed", NOT_ASKED);
                }
                yield new CustomerConversations.Open(
                        merchantId, QUOTE, request.id(), request.ref(), customerId, name, request.title(), null);
            }
            case BOOKING -> {
                var booking = bookings.find(customerId, refId).orElseThrow(() -> new NotFound("booking", refId));
                yield new CustomerConversations.Open(
                        booking.merchantId(),
                        BOOKING,
                        booking.id(),
                        booking.ref(),
                        customerId,
                        name,
                        booking.title(),
                        booking.memberUserId());
            }
            default -> throw RuleViolation.of("refType", "allowed", "Choose a quote request or a booking.");
        };
        return thread(customerId, conversations.open(open));
    }

    @Override
    public CustomerConversations.Message send(String customerId, String threadId, String body) {
        return conversations.send(customerId, threadId, body);
    }

    @Override
    public void read(String customerId, String threadId) {
        conversations.markRead(customerId, threadId);
    }

    private Map<String, PublicProviders.Provider> names(List<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return providers.published(merchantIds.stream().distinct().toList(), "en").stream()
                .collect(Collectors.toMap(PublicProviders.Provider::merchantId, Function.identity()));
    }

    private static ThreadRow row(CustomerConversations.CustomerThread t, Map<String, PublicProviders.Provider> names) {
        var provider = names.get(t.merchantId());
        var href = QUOTE.equals(t.refType()) ? "/quotes/requests/" + t.refId() : "/bookings/" + t.refId();
        return new ThreadRow(
                t.id(),
                t.refType(),
                t.refId(),
                t.refCode(),
                t.subject(),
                provider == null ? "" : provider.displayName(),
                provider == null ? "" : provider.slug(),
                href,
                t.lastMessageAt(),
                t.lastMessage(),
                t.unread());
    }
}
