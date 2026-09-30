package ca.northline.email;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link SentEmails} over {@code events.processed_events (consumer, event_id)} with {@code consumer = 'email'}: the
 * primary key makes the claim atomic across threads and instances. Claims are committed in their own transaction, so a
 * listener that fails for a later recipient and rolls back doesn't forget the emails that already went out. A crash
 * between the claim and the provider's answer loses that one email (at most once in that window).
 */
final class JdbcSentEmails implements SentEmails {

    static final String CONSUMER = "email";

    private final JdbcClient jdbc;
    private final @Nullable TransactionOperations separately;

    JdbcSentEmails(JdbcClient jdbc, @Nullable TransactionOperations separately) {
        this.jdbc = jdbc;
        this.separately = separately;
    }

    @Override
    public boolean claim(String key) {
        return separately(
                        () -> jdbc.sql("""
                                insert into events.processed_events (consumer, event_id) values (:c, :k)
                                on conflict do nothing""").param("c", CONSUMER).param("k", key).update())
                == 1;
    }

    @Override
    public void release(String key) {
        separately(() -> jdbc.sql("delete from events.processed_events where consumer = :c and event_id = :k")
                .param("c", CONSUMER)
                .param("k", key)
                .update());
    }

    private int separately(java.util.function.IntSupplier work) {
        if (separately == null) {
            return work.getAsInt();
        }
        var result = separately.execute(_ -> work.getAsInt());
        return result == null ? 0 : result;
    }
}
