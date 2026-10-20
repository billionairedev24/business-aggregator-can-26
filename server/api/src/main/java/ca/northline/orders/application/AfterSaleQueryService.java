package ca.northline.orders.application;

import ca.northline.merchants.api.BusinessNames;
import ca.northline.orders.application.AfterSaleUseCases.AfterSaleView;
import ca.northline.orders.application.AfterSaleUseCases.CancellationRecord;
import ca.northline.orders.application.AfterSaleUseCases.OrderAfterSale;
import ca.northline.orders.application.TrackOrder.FailureInfo;
import ca.northline.shared.NotFound;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link OrderAfterSale} (S-151): the console's drawer for one order. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AfterSaleQueryService implements OrderAfterSale {

    private final AfterSaleStore store;
    private final ReturnService returns;
    private final BusinessNames names;

    @Override
    public AfterSaleView of(String orderId) {
        var order = store.find(orderId).orElseThrow(() -> new NotFound("order", orderId));
        var cancellations = store.cancellations(orderId).stream()
                .map(c -> new CancellationRecord(
                        c.id(),
                        c.merchantId(),
                        c.merchantId() == null
                                ? null
                                : names.displayName(c.merchantId()).orElse(null),
                        c.actorRole(),
                        c.actorId(),
                        c.reason(),
                        c.note(),
                        c.restock(),
                        c.whole(),
                        c.lineIds().size(),
                        c.at()))
                .toList();
        var failure = store.failure(orderId)
                .map(f -> new FailureInfo(
                        f.reason(), f.failedAt(), f.decideBy(), f.attempts(), f.choice(), f.chosenAt(), f.feeCents()))
                .orElse(null);
        return new AfterSaleView(orderId, order.state(), cancellations, failure, returns.ofOrder(orderId));
    }
}
