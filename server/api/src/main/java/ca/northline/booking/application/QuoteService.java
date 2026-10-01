package ca.northline.booking.application;

import ca.northline.booking.application.QuoteRequests.Request;
import ca.northline.booking.application.QuoteUseCases.AcceptQuote;
import ca.northline.booking.application.QuoteUseCases.DeclineQuoteRequest;
import ca.northline.booking.application.QuoteUseCases.ListQuoteRequests;
import ca.northline.booking.application.QuoteUseCases.ReviseQuote;
import ca.northline.booking.application.QuoteUseCases.SendQuote;
import ca.northline.booking.application.QuoteUseCases.ViewQuote;
import ca.northline.booking.application.QuoteViews.QuoteRequestCard;
import ca.northline.booking.application.QuoteViews.QuoteView;
import ca.northline.booking.domain.Quote;
import ca.northline.booking.domain.QuoteContent;
import ca.northline.booking.domain.QuoteEnums.QuoteState;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.TaxRates;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quote requests and the quote composer. Every send / revision computes the totals from the lines ({@code QuoteTotals})
 * and publishes {@code quote.sent} in the same transaction; the V016 trigger re-checks subtotal = Σ lines at commit.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class QuoteService implements ListQuoteRequests, SendQuote, ReviseQuote, DeclineQuoteRequest, ViewQuote, AcceptQuote {

    static final String ATTACHMENT_NOT_FOUND = "This file wasn't uploaded to this business.";

    private final QuoteRequests requests;
    private final QuoteRepository quotes;
    private final MediaCatalog media;
    private final PersonDirectory people;
    private final TaxRates taxRates;
    private final MerchantPlaces places;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public List<QuoteRequestCard> list(String merchantId) {
        var open = requests.open(merchantId, clock.instant());
        var current = quotes.current(merchantId, open.stream().map(Request::id).toList()).stream()
                .collect(Collectors.toMap(Quote::getRequestId, Function.identity()));
        var names = people.people(
                open.stream().map(Request::customerId).filter(Objects::nonNull).toList());
        return open.stream()
                .map(r -> {
                    var customer = r.customerId() == null ? null : names.get(r.customerId());
                    var quote = current.get(r.id());
                    return new QuoteRequestCard(
                            r.id(),
                            r.quoteRef(),
                            r.title(),
                            customer == null ? null : customer.shortName(),
                            customer == null ? null : customer.reliabilityScore(),
                            r.area(),
                            r.body(),
                            r.preferredAt(),
                            r.createdAt(),
                            r.respondBy(),
                            r.expiresAt(),
                            quote == null ? null : withAttachments(quote));
                })
                .toList();
    }

    @Override
    @Transactional
    public QuoteView send(SendQuote.Command command) {
        var now = clock.instant();
        var request = requests.find(command.merchantId(), command.requestId())
                .orElseThrow(() -> new NotFound("quote request", command.requestId()));
        if (request.declined()) {
            throw new Conflict("request_declined", "You declined this request.");
        }
        if (request.expiresAt() != null && !now.isBefore(request.expiresAt())) {
            throw new Conflict("request_expired", "This request has expired.");
        }
        requireAttachments(command.merchantId(), command.content());
        int taxBps = taxBps(command.merchantId());
        var current = quotes.current(command.merchantId(), List.of(request.id())).stream()
                .findFirst();
        Quote quote;
        if (current.isPresent()) {
            quote = current.get();
            if (quote.getState() != QuoteState.DRAFT) {
                throw new Conflict(
                        "quote_already_sent", "You already sent a quote for this request. Revise it instead.");
            }
            quote.redraft(command.content(), taxBps);
            quotes.updateDraft(quote);
        } else {
            quote = Quote.draft(
                    request.id(),
                    command.merchantId(),
                    request.quoteRef(),
                    1,
                    command.content(),
                    taxBps,
                    command.actorId(),
                    now);
            quotes.insertDraft(quote);
        }
        var sent = quote.send(command.actorId(), now, null);
        quotes.updateLifecycle(quote);
        events.publishEvent(sent);
        return withAttachments(quote);
    }

    @Override
    @Transactional
    public QuoteView revise(ReviseQuote.Command command) {
        var now = clock.instant();
        var prior = quotes.find(command.merchantId(), command.quoteId())
                .orElseThrow(() -> new NotFound("quote", command.quoteId()));
        requireAttachments(command.merchantId(), command.content());
        var next = prior.revise(command.content(), taxBps(command.merchantId()), command.actorId(), now);
        quotes.updateLifecycle(prior);
        quotes.insertDraft(next);
        var sent = next.send(command.actorId(), now, prior.getId());
        quotes.updateLifecycle(next);
        events.publishEvent(sent);
        return withAttachments(next);
    }

    @Override
    @Transactional
    public void decline(String merchantId, String requestId, String actorId) {
        var request = requests.find(merchantId, requestId).orElseThrow(() -> new NotFound("quote request", requestId));
        if (!request.declined()) {
            requests.decline(requestId, merchantId, actorId, clock.instant());
        }
    }

    @Override
    public QuoteView view(String merchantId, String quoteId) {
        return withAttachments(quotes.find(merchantId, quoteId).orElseThrow(() -> new NotFound("quote", quoteId)));
    }

    @Override
    @Transactional
    public Quote accept(String quoteId, String customerId) {
        var quote = quotes.findById(quoteId).orElseThrow(() -> new NotFound("quote", quoteId));
        var request = requests.find(quote.getMerchantId(), quote.getRequestId())
                .orElseThrow(() -> new NotFound("quote request", quote.getRequestId()));
        var accepted = quote.accept(customerId, Objects.requireNonNullElse(request.customerId(), ""), clock.instant());
        quotes.updateLifecycle(quote);
        events.publishEvent(accepted);
        return quote;
    }

    private void requireAttachments(String merchantId, QuoteContent content) {
        var ids = content.attachments();
        if (ids.isEmpty()) {
            return;
        }
        var found = media.find(merchantId, ids).stream()
                .map(MediaCatalog.MediaInfo::id)
                .toList();
        for (int i = 0; i < ids.size(); i++) {
            if (!found.contains(ids.get(i))) {
                throw RuleViolation.of("attachments[%d]".formatted(i), "not_found", ATTACHMENT_NOT_FOUND);
            }
        }
    }

    private QuoteView withAttachments(Quote quote) {
        var ids = quote.getContent().attachments();
        if (ids.isEmpty()) {
            return new QuoteView(quote, List.of());
        }
        var byId = media.find(quote.getMerchantId(), ids).stream()
                .collect(Collectors.toMap(MediaCatalog.MediaInfo::id, Function.identity()));
        return new QuoteView(
                quote, ids.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    /** The rate of the business's own province (else the configured default province; region model, S-134). */
    private int taxBps(String merchantId) {
        var province = places.of(merchantId).province();
        return province == null ? taxRates.bpsFor("") : taxRates.bpsFor(province);
    }
}
