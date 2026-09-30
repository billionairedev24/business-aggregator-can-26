package ca.northline.email;

/**
 * Which deliveries already happened, so a listener or consumer that runs again for the same event — or twice at the
 * same time — doesn't email twice. A delivery is claimed before it is sent and released if the provider couldn't take
 * it. The JDBC implementation uses {@code events.processed_events} (consumer {@code email}), which the api and the
 * worker share — whichever claims first sends.
 */
public interface SentEmails {

    /** Takes {@code key}; false when it was taken (sent, being sent, or rejected) before. */
    boolean claim(String key);

    /** Gives {@code key} back after a failed send, so a retry sends it. */
    void release(String key);

    /** No memory at all (no database): every delivery is sent. */
    SentEmails NONE = new SentEmails() {
        @Override
        public boolean claim(String key) {
            return true;
        }

        @Override
        public void release(String key) {
            // nothing to forget
        }
    };
}
