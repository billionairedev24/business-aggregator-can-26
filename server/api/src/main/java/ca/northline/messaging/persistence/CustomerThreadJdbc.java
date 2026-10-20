package ca.northline.messaging.persistence;

import static ca.northline.messaging.persistence.MessagingSql.instant;
import static ca.northline.messaging.persistence.MessagingSql.ts;

import ca.northline.messaging.api.CustomerConversations.CustomerThread;
import ca.northline.messaging.application.CustomerThreadStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CustomerThreadStore} over {@code messaging.threads / messages} (S-153; index {@code ix_threads_customer}). */
@Repository
@RequiredArgsConstructor
class CustomerThreadJdbc implements CustomerThreadStore {

    private static final String THREADS = """
            select t.id, t.merchant_id, t.ref_type, t.ref_id, t.ref_code, t.subject, t.last_message_at,
                   lm.body as last_body,
                   exists (select 1 from messaging.messages m
                            where m.thread_id = t.id and m.sender_role <> 'customer'
                              and m.at > coalesce(t.customer_read_at, '-infinity')) as unread
              from messaging.threads t
              left join lateral (select m.body from messaging.messages m where m.thread_id = t.id
                                  order by m.at desc, m.id desc limit 1) lm on true
             where t.kind = 'customer' and t.counterpart_id = :c and t.ref_type is not null and t.ref_id is not null
            """;

    private final JdbcClient jdbc;

    @Override
    public List<CustomerThread> list(String customerId) {
        return jdbc.sql(THREADS + " order by t.last_message_at desc nulls last, t.id desc limit 200")
                .param("c", customerId)
                .query((rs, _) -> thread(rs))
                .list();
    }

    @Override
    public Optional<CustomerThread> find(String customerId, String threadId) {
        return jdbc.sql(THREADS + " and t.id = :id")
                .param("c", customerId)
                .param("id", threadId)
                .query((rs, _) -> thread(rs))
                .optional();
    }

    @Override
    public void markRead(String threadId, Instant at) {
        jdbc.sql("""
                        update messaging.threads set customer_read_at = greatest(coalesce(customer_read_at, :at), :at)
                         where id = :t
                        """).param("t", threadId).param("at", ts(at)).update();
    }

    @Override
    public Optional<String> customerOf(String threadId) {
        return jdbc.sql("""
                        select counterpart_id from messaging.threads
                         where id = :t and kind = 'customer' and counterpart_id is not null""").param("t", threadId).query(String.class).optional();
    }

    private static CustomerThread thread(ResultSet rs) throws SQLException {
        return new CustomerThread(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getString("ref_code"),
                rs.getString("subject"),
                instant(rs, "last_message_at"),
                rs.getString("last_body"),
                rs.getBoolean("unread"));
    }
}
