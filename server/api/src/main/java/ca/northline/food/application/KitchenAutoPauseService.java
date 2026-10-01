package ca.northline.food.application;

import ca.northline.food.api.KitchenAutoPaused;
import ca.northline.food.api.KitchenAutoResumed;
import ca.northline.food.application.AutoPauseStore.AutoPauseRow;
import ca.northline.food.application.KitchenUseCases.KitchenAutoPause;
import ca.northline.shared.Ids;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link KitchenAutoPause}: one conditional update per change, so two replicas never publish the same transition; the
 * scheduler's sweep runs each kitchen in its own transaction (its event commits with its mark).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class KitchenAutoPauseService implements KitchenAutoPause {

    private final AutoPauseStore store;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final PlatformTransactionManager transactions;

    @Override
    @Transactional
    public void check(String merchantId) {
        store.of(merchantId, clock.instant()).ifPresent(this::apply);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int checkAll() {
        var perKitchen = new TransactionTemplate(transactions);
        int changed = 0;
        for (var row : store.watched(clock.instant())) {
            try {
                if (Boolean.TRUE.equals(perKitchen.execute(_ -> apply(row)))) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.warn("Auto-pause check failed for kitchen {}", row.merchantId(), e);
            }
        }
        return changed;
    }

    private boolean apply(AutoPauseRow row) {
        var now = clock.instant();
        var threshold = row.threshold();
        var late = threshold != null && row.lateOrders() >= threshold;
        if (threshold != null && late && row.pausedAt() == null && store.markPaused(row.merchantId(), now)) {
            events.publishEvent(new KitchenAutoPaused(Ids.next(), now, row.merchantId(), row.lateOrders(), threshold));
            return true;
        }
        if (!late && row.pausedAt() != null && store.markResumed(row.merchantId())) {
            events.publishEvent(new KitchenAutoResumed(Ids.next(), now, row.merchantId()));
            return true;
        }
        return false;
    }
}
