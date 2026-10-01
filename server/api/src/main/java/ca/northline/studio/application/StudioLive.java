package ca.northline.studio.application;

import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-68): "something on this business's live screens changed", between api replicas, so the Studio's
 * live stream ({@code GET …/live}) is pushed from whichever replica holds the browser's connection. In-process with
 * {@code northline.live.bus=memory} (local, test); Valkey pub/sub with {@code redis} (the cloud profiles). Carries a
 * topic and an id only — never message text, names or amounts — and stores nothing: a browser that was not connected
 * catches up with one read when it reconnects.
 */
public interface StudioLive {

    void signal(String merchantId, Signal signal);

    /** Calls {@code listener} for every signal of the merchant until the subscription is closed. */
    Subscription subscribe(String merchantId, Consumer<Signal> listener);

    /** What changed: the screen's {@link Topic} and the thread or order id, when there is one. */
    record Signal(Topic topic, @Nullable String ref) {

        /** {@code topic} or {@code topic:ref} — the pub/sub message body. */
        public String encode() {
            return ref == null ? topic.code() : topic.code() + ":" + ref;
        }

        public static @Nullable Signal decode(String body) {
            var colon = body.indexOf(':');
            var topic = Topic.of(colon < 0 ? body : body.substring(0, colon));
            return topic == null ? null : new Signal(topic, colon < 0 ? null : body.substring(colon + 1));
        }
    }

    /** The SSE event name; the Studio refreshes the matching screen's data. */
    enum Topic {
        /** Messages and help cases: a message was added to one of the business's threads. */
        MESSAGE("message"),
        /** The kitchen display: a food order arrived or moved, or the kitchen paused/resumed. */
        KITCHEN("kitchen"),
        /** Orders (goods): an order arrived or was packed. */
        ORDERS("orders");

        private final String code;

        Topic(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        static @Nullable Topic of(String code) {
            for (var t : values()) {
                if (t.code.equals(code)) {
                    return t;
                }
            }
            return null;
        }
    }

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
