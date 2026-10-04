package ca.northline.golive.adapters;

import ca.northline.booking.api.BookingConfirmed;
import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.messaging.api.TicketOpened;
import ca.northline.orders.api.OrderPlaced;
import ca.northline.payments.api.PayoutFailed;
import ca.northline.payments.api.PayoutSent;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Regions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * S-118 hypercare: the market's activity as one counter, {@code northline.market.activity} by {@code market} (a region
 * market id, {@code none} for a business without one) and {@code kind} ({@code order}, {@code booking},
 * {@code payout_sent}, {@code payout_failed}, {@code support_ticket}) — the hypercare dashboard's per-market panels
 * (deploy/observability/grafana/dashboards.py {@code hypercare}). The other S-111 metrics carry no place. Counted once
 * the transaction commits; plain {@code @EventListener}, so no outbox row per event. Market ids are a handful of values.
 */
@Component
@RequiredArgsConstructor
class MarketActivityMetrics {

    static final String ACTIVITY = "northline.market.activity";
    private static final int CACHE_LIMIT = 20_000;

    private final MeterRegistry meters;
    private final MerchantDirectory merchants;
    private final Regions regions;
    private final Map<String, String> marketOf = new ConcurrentHashMap<>();

    @EventListener
    void on(OrderPlaced e) {
        count(e.merchantId(), "order");
    }

    @EventListener
    void on(BookingConfirmed e) {
        count(e.merchantId(), "booking");
    }

    @EventListener
    void on(PayoutSent e) {
        count(e.merchantId(), "payout_sent");
    }

    @EventListener
    void on(PayoutFailed e) {
        count(e.merchantId(), "payout_failed");
    }

    @EventListener
    void on(TicketOpened e) {
        count(e.merchantId(), "support_ticket");
    }

    private void count(String merchantId, String kind) {
        Runnable action = () -> meters.counter(ACTIVITY, "market", market(merchantId), "kind", kind)
                .increment();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String market(String merchantId) {
        var known = marketOf.get(merchantId);
        if (known != null) {
            return known;
        }
        var market = merchants
                .profile(merchantId)
                .flatMap(p -> regions.market(p.city(), p.province()))
                .map(MarketProfile::id)
                .orElse("none");
        if (marketOf.size() < CACHE_LIMIT) {
            marketOf.put(merchantId, market);
        }
        return market;
    }
}
