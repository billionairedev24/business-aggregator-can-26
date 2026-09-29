package ca.northline.booking;

import ca.northline.shared.Ids;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
class QuoteService {
    private final QuoteRepository quotes;
    private final ApplicationEventPublisher events;
    QuoteService(QuoteRepository quotes, ApplicationEventPublisher events) { this.quotes = quotes; this.events = events; }

    /** State change + event row commit together (Modulith event publication registry = outbox). */
    @Transactional
    public void accept(String quoteId, String customerId) {
        var q = quotes.findById(quoteId).orElseThrow();
        var accepted = q.accept(customerId); // enforces validity, version, status
        quotes.save(accepted);
        events.publishEvent(new QuoteAccepted(Ids.next(), Instant.now(), q.id(), q.merchantId(), customerId, q.totalCents(), q.depositCents()));
    }
}
