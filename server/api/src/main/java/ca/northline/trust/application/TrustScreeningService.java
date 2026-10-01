package ca.northline.trust.application;

import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import ca.northline.messaging.api.MessageTexts;
import ca.northline.shared.Ids;
import ca.northline.trust.api.ListingTexts;
import ca.northline.trust.application.AiScreeningStore.Mark;
import ca.northline.trust.application.AiScreeningStore.Screening;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ScreenTrustContent}: per source, under a database lock (one replica at a time), read the items after the
 * source's mark, screen each with {@link TrustScreener}, record the verdict and raise an {@code ai_screen} flag for the
 * flagged ones, then move the mark. When the AI is unavailable or over its budget the source stops where it is and the
 * next run continues from there; nothing is skipped.
 */
@Slf4j
@Service
@EnableConfigurationProperties(TrustAiProperties.class)
class TrustScreeningService implements ScreenTrustContent {

    static final String RULE = "ai_screen";
    static final String ACTOR = Caller.SYSTEM + TrustScreener.JOB;

    private final TrustScreener screener;
    private final AiScreeningStore store;
    private final TrustFlagStore flags;
    private final ListingTexts listings;
    private final MessageTexts messages;
    private final TrustAiProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    TrustScreeningService(
            TrustScreener screener,
            AiScreeningStore store,
            TrustFlagStore flags,
            ListingTexts listings,
            MessageTexts messages,
            TrustAiProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.screener = screener;
        this.store = store;
        this.flags = flags;
        this.listings = listings;
        this.messages = messages;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** One item of a source: what to screen, where it belongs, and its place in the source's order. */
    private record Candidate(
            String targetType,
            String targetId,
            String merchantId,
            Mark position,
            TrustScreener.Item item,
            Map<String, String> evidence) {}

    @Override
    public Result screenNew() {
        var screened = new AtomicInteger();
        var flagged = new AtomicInteger();
        var deferred = new ArrayList<String>();
        var sources = new ArrayList<>(List.of("listing", "review"));
        if (properties.screenMessages()) {
            sources.add("message");
        }
        for (var source : sources) {
            var stopped = transactions.execute(tx -> runSource(source, screened, flagged));
            if (Boolean.TRUE.equals(stopped)) {
                deferred.add(source);
            }
        }
        return new Result(screened.get(), flagged.get(), List.copyOf(deferred));
    }

    /** True when the source stopped early (AI unavailable or rate-limited). */
    private boolean runSource(String source, AtomicInteger screened, AtomicInteger flagged) {
        if (!store.lock(source)) {
            return false; // another replica is on it
        }
        var mark =
                store.mark(source).orElseGet(() -> new Mark(clock.instant().minus(properties.initialLookback()), ""));
        var batch = Math.clamp(properties.batch(), 1, 200);
        var candidates = switch (source) {
            case "listing" ->
                listings.submittedAfter(mark.at(), mark.id(), batch).stream()
                        .map(TrustScreeningService::listing)
                        .toList();
            case "review" ->
                store.reviewsAfter(mark, batch).stream()
                        .map(TrustScreeningService::review)
                        .toList();
            default ->
                messages.after(mark.at(), mark.id(), batch).stream()
                        .map(TrustScreeningService::message)
                        .toList();
        };
        var stopped = false;
        var last = mark;
        for (var c : candidates) {
            TrustScreener.Verdict verdict;
            try {
                verdict = screener.screen(c.item());
            } catch (AiUnavailable | AiRateLimited e) {
                log.info("Trust screening of {}s paused: {}", source, e.getMessage());
                stopped = true;
                break;
            }
            var screeningId = Ids.next();
            store.record(new Screening(
                    screeningId,
                    c.targetType(),
                    c.targetId(),
                    c.merchantId(),
                    verdict.flag(),
                    verdict.categories(),
                    verdict.flag() ? verdict.explanation() : null,
                    verdict.model(),
                    verdict.prompt()));
            screened.incrementAndGet();
            if (verdict.flag()) {
                var evidence = new LinkedHashMap<>(c.evidence());
                evidence.put("source", "ai");
                evidence.put("categories", String.join(",", verdict.categories()));
                evidence.put("explanation", verdict.explanation());
                evidence.put("model", verdict.model());
                evidence.put("prompt", verdict.prompt());
                evidence.put("screeningId", screeningId);
                if (flags.raiseUnlessOpen(
                        Ids.next(), c.targetType(), c.targetId(), RULE, c.merchantId(), ACTOR, evidence)) {
                    flagged.incrementAndGet();
                }
            }
            last = c.position();
        }
        if (!last.equals(mark) || store.mark(source).isEmpty()) {
            store.saveMark(source, last);
        }
        return stopped;
    }

    private static Candidate listing(ListingTexts.ListingText l) {
        var hints = new ArrayList<String>();
        for (var f : l.vettingFlags()) {
            hints.add("automated vetting: " + f.replace('_', ' '));
        }
        var price = l.priceCents();
        var median = l.categoryMedianCents();
        if (price != null && median != null && median > 0) {
            var deviation = Math.round((price - median) * 100.0 / median);
            hints.add(String.format(
                    Locale.ROOT,
                    "price %d %% %s the category median",
                    Math.abs(deviation),
                    deviation < 0 ? "below" : "above"));
        }
        var context = new StringBuilder("a ").append(l.kind()).append(" listing submitted for vetting");
        if (l.category() != null) {
            context.append(", category ").append(l.category());
        }
        context.append(price == null ? ", priced by quote" : ", price " + dollars(price));
        var text = l.details().isBlank() ? l.name() : l.name() + "\n" + l.details();
        return new Candidate(
                "listing",
                l.listingId(),
                l.merchantId(),
                new Mark(l.submittedAt(), l.listingId()),
                new TrustScreener.Item("listing", context.toString(), hints, text),
                Map.of("kind", l.kind()));
    }

    private static Candidate review(AiScreeningStore.ReviewText r) {
        return new Candidate(
                "review",
                r.reviewId(),
                r.merchantId(),
                new Mark(r.createdAt(), r.reviewId()),
                new TrustScreener.Item(
                        "review",
                        "a customer's review of a business, " + r.rating() + " of 5 stars",
                        List.of(),
                        r.text()),
                Map.of());
    }

    private static Candidate message(MessageTexts.MessageText m) {
        var context = "merchant".equals(m.senderRole())
                ? "a message from the business to a customer"
                : "a message from a customer to the business";
        var hints = m.detectorFlagged()
                ? List.of("the off-platform detector found masked contact details or payment words")
                : List.<String>of();
        return new Candidate(
                "message",
                m.messageId(),
                m.merchantId(),
                new Mark(m.at(), m.messageId()),
                new TrustScreener.Item("message", context, hints, m.body()),
                Map.of("threadId", m.threadId(), "senderRole", m.senderRole()));
    }

    private static String dollars(long cents) {
        return String.format(Locale.ROOT, "$%d.%02d", cents / 100, cents % 100);
    }
}
