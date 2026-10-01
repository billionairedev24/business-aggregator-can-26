package ca.northline.orders.application;

/**
 * Outbound port: "this order changed" between api replicas, so an order's live tracking stream (SSE) is pushed from
 * whichever replica holds the connection. In-process under {@code local} / {@code test}; Redis pub/sub elsewhere
 * (CLAUDE.md: "Redis … order tracking pub/sub"). Carries order ids only.
 */
public interface TrackingBus {

    void changed(String orderId);

    /** Calls {@code onChange} whenever the order changes, until the subscription is closed. */
    Subscription subscribe(String orderId, Runnable onChange);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
