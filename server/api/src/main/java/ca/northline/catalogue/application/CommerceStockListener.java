package ca.northline.catalogue.application;

import ca.northline.catalogue.application.StockSync.RecordStockChanges;
import ca.northline.orders.api.OrderPlaced;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * S-158: a paid goods order takes its units off the connected platforms too (async after commit, retried from the
 * event registry; the pushes are unique per order line, so a redelivered event queues nothing new). Food lines are
 * kitchen menu items, never linked to a platform.
 */
@Component
@RequiredArgsConstructor
class CommerceStockListener {

    private final RecordStockChanges changes;

    @ApplicationModuleListener
    void on(OrderPlaced event) {
        for (var line : event.lines()) {
            if (!"offer".equals(line.itemKind()) || line.qty() <= 0) {
                continue;
            }
            changes.record(new RecordStockChanges.Change(
                    event.merchantId(),
                    line.offerId(),
                    line.variantId(),
                    -line.qty(),
                    "sale",
                    event.aggregateId(),
                    "sale:" + event.aggregateId() + ":" + line.lineId()));
        }
    }
}
