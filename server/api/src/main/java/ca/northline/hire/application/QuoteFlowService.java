package ca.northline.hire.application;

import ca.northline.availability.api.ProviderSlots;
import ca.northline.booking.api.CustomerBookings;
import ca.northline.booking.api.CustomerQuotes;
import ca.northline.booking.api.CustomerQuotes.CustomerQuote;
import ca.northline.booking.api.CustomerQuotes.CustomerRequest;
import ca.northline.catalogue.api.ServiceOffers;
import ca.northline.hire.application.BookingCheckout.Confirmation;
import ca.northline.hire.application.BookingCheckout.ViewBooking;
import ca.northline.hire.application.QuoteFlow.Acceptance;
import ca.northline.hire.application.QuoteFlow.CompareQuotes;
import ca.northline.hire.application.QuoteFlow.Comparison;
import ca.northline.hire.application.QuoteFlow.ConfirmAcceptance;
import ca.northline.hire.application.QuoteFlow.DeclineQuote;
import ca.northline.hire.application.QuoteFlow.Offer;
import ca.northline.hire.application.QuoteFlow.Other;
import ca.northline.hire.application.QuoteFlow.ProviderSummary;
import ca.northline.hire.application.QuoteFlow.QuotePage;
import ca.northline.hire.application.QuoteFlow.RequestQuotes;
import ca.northline.hire.application.QuoteFlow.Requested;
import ca.northline.hire.application.QuoteFlow.StartAcceptance;
import ca.northline.hire.application.QuoteFlow.ViewQuote;
import ca.northline.hire.domain.QuoteAsk;
import ca.northline.hire.domain.QuoteVisit;
import ca.northline.hire.domain.ServiceKind;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.PublicProviders;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.PaymentSettings;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.QualityQuery;
import ca.northline.trust.api.RatingQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-56. The request goes to 1–3 published providers that offer the category; each answers with an itemized quote in
 * Studio (versions: a revision supersedes the prior one). Accepting holds the quote's deposit — or the whole quote when
 * it asks for none — in escrow (S-11) on a manual-capture PaymentIntent for the booking chosen up front, then accepts
 * the quote ({@code quote.accepted}) and books the proposed time ({@code booking.confirmed}).
 */
@Service
@RequiredArgsConstructor
class QuoteFlowService
        implements RequestQuotes, CompareQuotes, ViewQuote, DeclineQuote, StartAcceptance, ConfirmAcceptance {

    /** A requested date means "from 9 am" to the provider (the Studio card shows a time). */
    static final LocalTime PREFERRED_TIME = LocalTime.of(9, 0);

    static final String NOT_OFFERED = "One of these providers doesn't offer this service any more. Choose again.";
    static final String NO_TIME = "This quote has no date yet. Ask the provider to propose one.";
    static final String NOT_FREE = "The provider is no longer free at the proposed time. Ask them for a new one.";
    static final String CLOSED = "This quote can no longer be accepted.";
    static final String EXPIRED = "This quote has expired. Ask for an updated quote.";
    static final String NOT_STARTED = "Start the payment first.";
    static final int DEFAULT_DURATION_MIN = 60;

    private final BrowseServices.ViewCategory categories;
    private final PublicProviders providers;
    private final ServiceOffers offers;
    private final CustomerQuotes quotes;
    private final RatingQuery ratings;
    private final QualityQuery quality;
    private final PaymentGate gate;
    private final PaymentAuthorizations payments;
    private final PaymentSettings paymentSettings;
    private final EscrowLifecycle escrow;
    private final CustomerBookings bookings;
    private final ViewBooking bookingViews;
    private final ProviderSlots slots;
    private final PersonDirectory people;
    private final HireProperties region;
    private final Clock clock;

    @Override
    public Requested request(String customerId, QuoteAsk ask) {
        var category = categories.category(ask.category(), "en");
        var today = LocalDate.now(clock.withZone(region.timeZone()));
        ask.validate(category.kind(), category.vehicle(), today);
        var chosen = new ArrayList<String>();
        for (var slug : ask.providers()) {
            var provider = providers.bySlug(slug, "en")
                    .orElseThrow(() -> RuleViolation.of("providers", "not_offered", NOT_OFFERED));
            boolean offered = offers.ofMerchant(provider.merchantId(), "en").stream()
                    .anyMatch(o -> category.id().equals(o.categoryId()));
            if (!offered) {
                throw RuleViolation.of("providers", "not_offered", NOT_OFFERED);
            }
            chosen.add(provider.merchantId());
        }
        var day = ask.eventDate() != null ? ask.eventDate() : ask.preferredDate();
        var ref = quotes.request(new CustomerQuotes.NewRequest(
                customerId,
                category.id(),
                chosen,
                ask.title(Objects.requireNonNullElse(category.names().get("en"), category.slug())),
                ask.text(),
                blankToNull(ask.area()),
                day == null ? null : day.atTime(PREFERRED_TIME).atZone(region.timeZone()).toInstant(),
                ask.details()));
        return new Requested(ref.id(), ref.ref(), ref.respondBy(), ref.expiresAt(), chosen.size());
    }

    @Override
    public Comparison compare(String customerId, String requestId, String lang) {
        var r = ownRequest(customerId, requestId);
        var summaries = summaries(r.merchantIds(), lang);
        var quoted = r.quotes().stream().collect(Collectors.toMap(CustomerQuote::merchantId, Function.identity()));
        var rows = r.merchantIds().stream()
                .filter(summaries::containsKey)
                .map(id -> {
                    var q = quoted.get(id);
                    var status = q != null ? "quoted" : r.declinedBy().contains(id) ? "declined" : "waiting";
                    return new Offer(summaries.get(id), status, q);
                })
                .sorted((a, b) -> {
                    var qa = a.quote();
                    var qb = b.quote();
                    if (qa == null || qb == null) {
                        return qa == null ? (qb == null ? 0 : 1) : -1;
                    }
                    return Long.compare(qa.totalCents(), qb.totalCents());
                })
                .toList();
        return new Comparison(
                r.id(),
                r.ref(),
                slugOf(r.categoryId()),
                r.title(),
                r.description(),
                r.area(),
                r.preferredAt(),
                r.createdAt(),
                r.respondBy(),
                r.expiresAt(),
                rows);
    }

    @Override
    @Transactional
    public QuotePage quote(String customerId, String quoteId, String lang) {
        quotes.markViewed(customerId, quoteId);
        var q = ownQuote(customerId, quoteId);
        var r = ownRequest(customerId, q.requestId());
        var summaries = summaries(r.merchantIds(), lang);
        var provider = summaries.get(q.merchantId());
        if (provider == null) {
            throw new NotFound("quote", quoteId);
        }
        var others = r.quotes().stream()
                .filter(o -> !o.merchantId().equals(q.merchantId()) && summaries.containsKey(o.merchantId()))
                .map(o -> new Other(o.id(), summaries.get(o.merchantId()).name(), o.totalCents(), o.state()))
                .toList();
        var acceptance = quotes.acceptance(customerId, quoteId)
                .filter(a -> a.acceptedAt() != null)
                .map(CustomerQuotes.Acceptance::bookingId)
                .orElse(null);
        return new QuotePage(q, provider, r.title(), r.area(), acceptance, others);
    }

    @Override
    public void decline(String customerId, String quoteId) {
        quotes.decline(customerId, quoteId);
    }

    @Override
    public Acceptance start(
            String customerId,
            String quoteId,
            QuoteVisit visit,
            @Nullable String clientKey,
            boolean mfa,
            @Nullable String stepUpProof) {
        var q = ownQuote(customerId, quoteId);
        requireOpen(q);
        var r = ownRequest(customerId, q.requestId());
        visit.validate(kind(r).comesToCustomer());
        startsAt(q, r);
        gate.require(customerId, mfa, stepUpProof);
        long held = q.depositCents() > 0 ? q.depositCents() : q.totalCents();
        long tax = q.totalCents() == 0
                ? 0
                : BigDecimal.valueOf(held)
                        .multiply(BigDecimal.valueOf(q.taxCents()))
                        .divide(BigDecimal.valueOf(q.totalCents()), 0, RoundingMode.HALF_UP)
                        .longValueExact();
        var bookingId = quotes.acceptance(customerId, quoteId)
                .map(CustomerQuotes.Acceptance::bookingId)
                .orElseGet(Ids::next);
        var started = payments.start(new PaymentAuthorizations.Request(
                q.merchantId(), "booking", bookingId, customerId, held - tax, tax, "booking:" + bookingId, clientKey));
        quotes.prepareAcceptance(new CustomerQuotes.Acceptance(
                quoteId, customerId, bookingId, started.paymentIntent(), held - tax, tax, null));
        return new Acceptance(
                quoteId,
                bookingId,
                held - tax,
                tax,
                held,
                started.status(),
                started.paymentIntent(),
                started.clientSecret(),
                paymentSettings.provider(),
                paymentSettings.publishableKey());
    }

    @Override
    @Transactional
    public Confirmation confirm(String customerId, String quoteId, QuoteVisit visit) {
        var a = quotes.acceptance(customerId, quoteId).orElseThrow(() -> new Conflict("not_started", NOT_STARTED));
        if (a.acceptedAt() != null) {
            return held(bookingViews.booking(customerId, a.bookingId()), a);
        }
        var q = ownQuote(customerId, quoteId);
        requireOpen(q);
        var r = ownRequest(customerId, q.requestId());
        var kind = kind(r);
        visit.validate(kind.comesToCustomer());
        var startsAt = startsAt(q, r);
        int duration = q.durationMin() == null ? DEFAULT_DURATION_MIN : q.durationMin();
        var member = slots.freeMember(q.merchantId(), startsAt, duration, customerId)
                .orElseThrow(() -> new Conflict("slot_taken", NOT_FREE));
        var customer = people.people(List.of(customerId)).get(customerId);
        var escrowId = escrow.hold(new EscrowLifecycle.Hold(
                q.merchantId(),
                EscrowKind.SERVICE,
                "booking",
                a.bookingId(),
                a.amountCents(),
                a.taxCents(),
                customerId,
                customer == null ? "Customer" : customer.shortName(),
                r.title(),
                null,
                null,
                null,
                "search",
                a.paymentIntent(),
                clock.instant()));
        quotes.accept(customerId, quoteId);
        var booked = bookings.book(new CustomerBookings.NewBooking(
                a.bookingId(),
                q.merchantId(),
                member,
                customerId,
                null,
                quoteId,
                kind.code(),
                r.title(),
                startsAt,
                startsAt.plusSeconds(duration * 60L),
                visit.address(),
                r.area(),
                Map.of("quoteRef", q.ref(), "quoteVersion", q.version()),
                visit.note(),
                visit.phone(),
                q.subtotalCents(),
                a.amountCents(),
                q.taxCents(),
                escrowId,
                null));
        return held(bookingViews.booking(customerId, booked.id()), a);
    }

    /** The confirmation with what this acceptance actually holds (the deposit and its share of the tax). */
    private static Confirmation held(Confirmation c, CustomerQuotes.Acceptance a) {
        return new Confirmation(
                c.bookingId(),
                c.ref(),
                c.providerName(),
                c.providerSlug(),
                c.memberFirstName(),
                c.title(),
                c.type(),
                c.startsAt(),
                c.endsAt(),
                c.addressLine(),
                c.priceCents(),
                c.taxCents(),
                a.amountCents() + a.taxCents(),
                c.freeCancelUntil());
    }

    private static void requireOpen(CustomerQuote q) {
        if ("superseded".equals(q.state())) {
            throw new Conflict("quote_revised", "The provider revised this quote. Review the new version before accepting.");
        }
        if (q.expired()) {
            throw new Conflict("quote_expired", EXPIRED);
        }
        if (!q.open()) {
            throw new Conflict("quote_state", CLOSED);
        }
    }

    private static Instant startsAt(CustomerQuote q, CustomerRequest r) {
        var at = q.proposedAt() != null ? q.proposedAt() : r.preferredAt();
        if (at == null) {
            throw new Conflict("quote_no_time", NO_TIME);
        }
        return at;
    }

    private ServiceKind kind(CustomerRequest r) {
        var id = r.categoryId();
        return id == null ? ServiceKind.VISIT : ServiceKind.of(id, null);
    }

    private CustomerRequest ownRequest(String customerId, String requestId) {
        return quotes.request(customerId, requestId).orElseThrow(() -> new NotFound("quote request", requestId));
    }

    private CustomerQuote ownQuote(String customerId, String quoteId) {
        return quotes.quote(customerId, quoteId).orElseThrow(() -> new NotFound("quote", quoteId));
    }

    private Map<String, ProviderSummary> summaries(List<String> merchantIds, String lang) {
        return providers.published(merchantIds, lang).stream()
                .collect(Collectors.toMap(PublicProviders.Provider::merchantId, p -> {
                    var rating = ratings.summary(p.merchantId());
                    var score = quality.latest(p.merchantId());
                    return new ProviderSummary(
                            p.merchantId(),
                            p.slug(),
                            p.displayName(),
                            p.tier(),
                            p.brandColor(),
                            rating.average(),
                            rating.count(),
                            QualityFigures.of(score, "on_time"),
                            QualityFigures.of(score, "disputes"),
                            p.verifiedFacts());
                }));
    }

    /** The leaf part of the category id ({@code service.automotive.mobile-mechanic} → {@code mobile-mechanic}). */
    private static @Nullable String slugOf(@Nullable String categoryId) {
        return categoryId == null ? null : categoryId.substring(categoryId.lastIndexOf('.') + 1);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
