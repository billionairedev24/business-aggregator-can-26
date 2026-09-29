package ca.northline.booking.persistence;

import ca.northline.booking.application.QuoteRequests;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code booking.quote_requests} as one merchant sees it. {@code details} (jsonb, written by the consumer request
 * flow) carries {@code title}, {@code description}, {@code area} and {@code preferredAt} (ISO-8601).
 */
@Repository
@RequiredArgsConstructor
class QuoteRequestQueries implements QuoteRequests {

    private static final String SELECT = """
            select r.id, r.number, r.customer_id, r.details ->> 'title' as title, r.details ->> 'description' as body,
                   r.details ->> 'area' as area, r.details ->> 'preferredAt' as preferred_at, r.created_at, r.respond_by,
                   r.expires_at, d.request_id is not null as declined
              from booking.quote_requests r
              left join booking.quote_request_declines d on d.request_id = r.id and d.merchant_id = :merchantId
             where :merchantId = any(r.merchant_ids)
            """;

    private final JdbcClient jdbc;

    @Override
    public List<Request> open(String merchantId, Instant now) {
        return jdbc.sql(SELECT + """
                           and d.request_id is null and (r.expires_at is null or r.expires_at > :now)
                         order by r.respond_by nulls last, r.created_at, r.id
                        """)
                .param("merchantId", merchantId)
                .param("now", JdbcTimes.ts(now))
                .query((rs, _) -> request(rs))
                .list();
    }

    @Override
    public Optional<Request> find(String merchantId, String requestId) {
        return jdbc.sql(SELECT + " and r.id = :id")
                .param("merchantId", merchantId)
                .param("id", requestId)
                .query((rs, _) -> request(rs))
                .optional();
    }

    @Override
    public void decline(String requestId, String merchantId, String actorId, Instant at) {
        jdbc.sql("""
                        insert into booking.quote_request_declines (request_id, merchant_id, declined_at, declined_by)
                        values (:requestId, :merchantId, :at, :actorId) on conflict do nothing
                        """)
                .param("requestId", requestId)
                .param("merchantId", merchantId)
                .param("at", JdbcTimes.ts(at))
                .param("actorId", actorId)
                .update();
    }

    private static Request request(ResultSet rs) throws SQLException {
        return new Request(
                rs.getString("id"),
                rs.getLong("number"),
                rs.getString("customer_id"),
                Objects.requireNonNullElse(rs.getString("title"), "Quote request"),
                rs.getString("body"),
                rs.getString("area"),
                parse(rs.getString("preferred_at")),
                JdbcTimes.requiredInstant(rs, "created_at"),
                JdbcTimes.instant(rs, "respond_by"),
                JdbcTimes.instant(rs, "expires_at"),
                rs.getBoolean("declined"));
    }

    private static @Nullable Instant parse(@Nullable String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (java.time.format.DateTimeParseException _) {
            return null;
        }
    }
}
