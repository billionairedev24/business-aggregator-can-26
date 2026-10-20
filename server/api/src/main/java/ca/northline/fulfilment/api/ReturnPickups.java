package ca.northline.fulfilment.api;

import ca.northline.fulfilment.api.DeliveryRequests.Dropoff;
import org.jspecify.annotations.Nullable;

/**
 * Inbound port (S-151): a return the shop approved for pickup. A courier collects it at the customer's door (a
 * {@code collect} stop) and brings it back to the shop (a {@code return} stop): on the market's next planned pooled run
 * when one starts soon, else on a direct run of its own. fulfilment publishes {@link ReturnCollected} and
 * {@link ReturnDelivered}. Idempotent per return (called from retried listeners).
 */
public interface ReturnPickups {

    void request(Request request);

    /** The return was withdrawn or settled another way before the courier came: its stops leave the run. */
    void cancel(String returnId);

    /**
     * @param collectFrom the customer's address (personal data: kept for the courier until 30 days after the pickup)
     * @param merchantId the shop it goes back to
     */
    record Request(
            String returnId,
            @Nullable String returnRef,
            String orderId,
            String merchantId,
            String market,
            @Nullable String customerId,
            Dropoff collectFrom) {}
}
