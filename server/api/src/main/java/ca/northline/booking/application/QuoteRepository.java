package ca.northline.booking.application;

import ca.northline.booking.domain.Quote;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for quotes. Writes follow the immutability rule enforced by the V040 triggers: content (and lines)
 * only change while a quote is a draft; afterwards only the lifecycle (state, sent/valid-until) moves.
 */
public interface QuoteRepository {

    Optional<Quote> find(String merchantId, String quoteId);

    /** Any merchant's quote (consumer side). */
    Optional<Quote> findById(String quoteId);

    /** The highest version per request for this merchant (draft or sent), for the given requests. */
    List<Quote> current(String merchantId, Collection<String> requestIds);

    /** Inserts a draft with its lines. */
    void insertDraft(Quote quote);

    /** Rewrites a draft's content and lines. */
    void updateDraft(Quote quote);

    /** Persists state, sent-at and valid-until. */
    void updateLifecycle(Quote quote);
}
