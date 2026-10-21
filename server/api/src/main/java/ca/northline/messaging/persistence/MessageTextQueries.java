package ca.northline.messaging.persistence;

import ca.northline.messaging.api.MessageTexts;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link MessageTexts}: customer-thread messages by time, keyset-paged by (at, id). */
@Repository
@RequiredArgsConstructor
class MessageTextQueries implements MessageTexts {

    private final JdbcClient jdbc;

    @Override
    public List<MessageText> after(Instant at, String id, int limit) {
        return jdbc.sql("""
                        select m.id, m.thread_id, t.merchant_id, m.sender_role, m.body, coalesce(m.flagged, false) as flagged,
                               m.at
                          from messaging.messages m
                          join messaging.threads t on t.id = m.thread_id
                         where t.kind = 'customer'
                           and t.merchant_id is not null
                           and m.sender_role in ('merchant', 'customer')
                           and m.at is not null
                           and coalesce(m.body, '') <> ''
                           and m.hidden_at is null
                           and (m.at, m.id) > (:at, :id)
                         order by m.at, m.id
                         limit :limit
                        """)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("id", id)
                .param("limit", limit)
                .query((rs, n) -> new MessageText(
                        rs.getString("id"),
                        rs.getString("thread_id"),
                        rs.getString("merchant_id"),
                        rs.getString("sender_role"),
                        rs.getString("body"),
                        rs.getBoolean("flagged"),
                        rs.getObject("at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public java.util.Optional<MessageText> find(String messageId) {
        return jdbc.sql("""
                        select m.id, m.thread_id, t.merchant_id, m.sender_role, coalesce(m.hidden_body, m.body) as body,
                               coalesce(m.flagged, false) as flagged, m.at
                          from messaging.messages m
                          join messaging.threads t on t.id = m.thread_id
                         where m.id = :id and t.merchant_id is not null and m.at is not null
                        """)
                .param("id", messageId)
                .query((rs, n) -> new MessageText(
                        rs.getString("id"),
                        rs.getString("thread_id"),
                        rs.getString("merchant_id"),
                        rs.getString("sender_role"),
                        java.util.Objects.requireNonNullElse(rs.getString("body"), ""),
                        rs.getBoolean("flagged"),
                        rs.getObject("at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public boolean hide(String messageId, String staffId, Instant at) {
        return jdbc.sql("""
                                update messaging.messages set hidden_body = body, body = :placeholder, hidden_at = :at,
                                  hidden_by = :by where id = :id and hidden_at is null""")
                        .param("placeholder", HIDDEN)
                        .param("at", at.atOffset(ZoneOffset.UTC))
                        .param("by", staffId)
                        .param("id", messageId)
                        .update()
                == 1;
    }
}
