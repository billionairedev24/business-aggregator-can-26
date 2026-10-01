package ca.northline.booking.application;

import ca.northline.booking.api.CustomerQuotes;
import ca.northline.booking.domain.Quote;
import ca.northline.booking.domain.QuoteEnums.QuoteState;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CustomerQuotes}: the request (answered within {@link #RESPOND_WITHIN}, open for {@link #OPEN_FOR}), the
 * customer's reading of every sent version, and accept / decline through the {@link Quote} aggregate, whose rules are
 * the merchant side's (immutable once sent, a revision supersedes the prior version).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CustomerQuoteService implements CustomerQuotes {

    /** Design 06: "Master-tier providers answer within 2 hours" — the response SLA of the Studio column. */
    static final Duration RESPOND_WITHIN = Duration.ofHours(2);

    /** How long providers can still answer. */
    static final Duration OPEN_FOR = Duration.ofDays(7);

    static final String REVISED = "The provider revised this quote. Review the new version before accepting.";

    private final CustomerQuoteStore store;
    private final QuoteRepository quotes;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public RequestRef request(NewRequest r) {
        var now = clock.instant();
        var details = new LinkedHashMap<String, Object>(r.details());
        details.put("title", r.title());
        details.put("description", r.description());
        if (r.area() != null) {
            details.put("area", r.area());
        }
        if (r.preferredAt() != null) {
            details.put("preferredAt", r.preferredAt().toString());
        }
        var id = Ids.next();
        var respondBy = now.plus(RESPOND_WITHIN);
        var expiresAt = now.plus(OPEN_FOR);
        long number = store.insertRequest(
                id, r.customerId(), r.categoryId(), details, r.merchantIds(), now, respondBy, expiresAt);
        return new RequestRef(id, "QT-" + number, respondBy, expiresAt);
    }

    @Override
    public Optional<CustomerRequest> request(String customerId, String requestId) {
        return store.request(customerId, requestId).map(r -> {
            var byMerchant = byMerchant(quotes.sentOf(r.id()));
            var latest = byMerchant.values().stream()
                    .map(versions -> view(versions.getFirst(), versions))
                    .sorted(Comparator.comparingLong(CustomerQuote::totalCents))
                    .toList();
            return new CustomerRequest(
                    r.id(),
                    r.ref(),
                    r.categoryId(),
                    r.title(),
                    r.description(),
                    r.area(),
                    r.preferredAt(),
                    r.createdAt(),
                    r.respondBy(),
                    r.expiresAt(),
                    r.merchantIds(),
                    store.declinedBy(r.id()),
                    latest);
        });
    }

    @Override
    public Optional<CustomerQuote> quote(String customerId, String quoteId) {
        return own(customerId, quoteId).map(q -> {
            var versions = byMerchant(quotes.sentOf(q.getRequestId())).getOrDefault(q.getMerchantId(), List.of(q));
            return view(q, versions);
        });
    }

    @Override
    @Transactional
    public void markViewed(String customerId, String quoteId) {
        own(customerId, quoteId)
                .filter(q -> q.getState() == QuoteState.SENT)
                .ifPresent(q -> store.markViewed(q.getId(), clock.instant()));
    }

    @Override
    @Transactional
    public void decline(String customerId, String quoteId) {
        var quote = own(customerId, quoteId).orElseThrow(() -> new NotFound("quote", quoteId));
        requireCurrent(quote);
        quote.decline(customerId, customerId);
        quotes.updateLifecycle(quote);
    }

    @Override
    @Transactional
    public void prepareAcceptance(Acceptance acceptance) {
        store.upsertAcceptance(acceptance);
    }

    @Override
    public Optional<Acceptance> acceptance(String customerId, String quoteId) {
        return store.acceptance(quoteId).filter(a -> a.customerId().equals(customerId));
    }

    @Override
    @Transactional
    public CustomerQuote accept(String customerId, String quoteId) {
        var quote = own(customerId, quoteId).orElseThrow(() -> new NotFound("quote", quoteId));
        var now = clock.instant();
        if (quote.getState() == QuoteState.ACCEPTED) {
            return quote(customerId, quoteId).orElseThrow();
        }
        requireCurrent(quote);
        var accepted = quote.accept(customerId, customerId, now);
        quotes.updateLifecycle(quote);
        store.markAccepted(quoteId, now);
        events.publishEvent(accepted);
        return quote(customerId, quoteId).orElseThrow();
    }

    /** The quote, when it's a sent version of one of the customer's own requests. */
    private Optional<Quote> own(String customerId, String quoteId) {
        return quotes.findById(quoteId)
                .filter(q -> q.getState() != QuoteState.DRAFT)
                .filter(q -> store.request(customerId, q.getRequestId()).isPresent());
    }

    private static void requireCurrent(Quote quote) {
        if (quote.getState() == QuoteState.SUPERSEDED) {
            throw new Conflict("quote_revised", REVISED);
        }
    }

    /** Each merchant's versions, newest first. */
    private static Map<String, List<Quote>> byMerchant(List<Quote> sent) {
        return sent.stream()
                .collect(Collectors.groupingBy(
                        Quote::getMerchantId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toCollection(ArrayList::new), list -> {
                            list.sort(Comparator.comparingInt(Quote::getVersion).reversed());
                            return List.copyOf(list);
                        })));
    }

    private CustomerQuote view(Quote q, List<Quote> versions) {
        var c = q.getContent();
        var t = q.getTotals();
        var validUntil = q.getValidUntil();
        return new CustomerQuote(
                q.getId(),
                q.getRequestId(),
                q.getRef(),
                q.getMerchantId(),
                q.getVersion(),
                q.getState().code(),
                validUntil != null
                        && !clock.instant().isBefore(validUntil)
                        && q.getState().isOpen(),
                c.scope(),
                c.exclusions(),
                c.proposedAt(),
                c.durationMin(),
                c.warranty().code(),
                c.depositKind().code(),
                c.depositBps(),
                c.lines().stream()
                        .map(l -> new Line(
                                l.kind().code(),
                                l.description(),
                                l.note(),
                                l.qty(),
                                l.unitCents(),
                                l.amountCents(),
                                l.taxable()))
                        .toList(),
                t.subtotalCents(),
                t.taxBps(),
                t.taxCents(),
                t.totalCents(),
                t.depositCents(),
                q.getSentAt(),
                validUntil,
                versions.stream()
                        .map(v -> new Version(
                                v.getId(),
                                v.getVersion(),
                                v.getState().code(),
                                v.getTotals().totalCents(),
                                v.getSentAt()))
                        .toList(),
                versions.isEmpty() ? q.getId() : versions.getFirst().getId());
    }
}
