package ca.northline.booking.persistence;

import ca.northline.booking.api.CustomerQuotes.Acceptance;
import ca.northline.booking.application.CustomerQuoteStore;
import ca.northline.shared.JdbcTimes;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link CustomerQuoteStore} over {@code booking.quote_requests} and {@code booking.quote_acceptances} (V116). */
@Repository
@RequiredArgsConstructor
class CustomerQuoteJdbc implements CustomerQuoteStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    @Override
    public long insertRequest(
            String id,
            String customerId,
            String categoryId,
            Map<String, Object> details,
            List<String> merchantIds,
            Instant createdAt,
            Instant respondBy,
            Instant expiresAt) {
        return jdbc.sql("""
                        insert into booking.quote_requests (id, customer_id, category_id, details, media, merchant_ids,
                               created_at, respond_by, expires_at)
                        values (:id, :customer, :category, cast(:details as jsonb), '{}', cast(:merchants as text[]),
                                :createdAt, :respondBy, :expiresAt)
                        returning number
                        """)
                .param("id", id)
                .param("customer", customerId)
                .param("category", categoryId)
                .param("details", JSON.writeValueAsString(details))
                .param("merchants", merchantIds.toArray(String[]::new))
                .param("createdAt", JdbcTimes.ts(createdAt))
                .param("respondBy", JdbcTimes.ts(respondBy))
                .param("expiresAt", JdbcTimes.ts(expiresAt))
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<Request> request(String customerId, String requestId) {
        return jdbc.sql("""
                        select id, number, customer_id, category_id, details ->> 'title' as title,
                               details ->> 'description' as description, details ->> 'area' as area,
                               details ->> 'preferredAt' as preferred_at, created_at, respond_by, expires_at, merchant_ids
                          from booking.quote_requests where id = :id and customer_id = :customer
                        """)
                .param("id", requestId)
                .param("customer", customerId)
                .query((rs, _) -> request(rs))
                .optional();
    }

    @Override
    public List<String> declinedBy(String requestId) {
        return jdbc
                .sql("select merchant_id from booking.quote_request_declines where request_id = :id")
                .param("id", requestId)
                .query((rs, _) -> rs.getString(1))
                .list()
                .stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public void markViewed(String quoteId, Instant at) {
        jdbc.sql("update booking.quotes set state = 'viewed', viewed_at = :at where id = :id and state = 'sent'")
                .param("id", quoteId)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    @Override
    public void upsertAcceptance(Acceptance a) {
        jdbc.sql("""
                        insert into booking.quote_acceptances (quote_id, customer_id, booking_id, payment_intent, amount_cents,
                               tax_cents)
                        values (:quote, :customer, :booking, :intent, :amount, :tax)
                        on conflict (quote_id) do update set payment_intent = excluded.payment_intent,
                               amount_cents = excluded.amount_cents, tax_cents = excluded.tax_cents, started_at = now()
                         where booking.quote_acceptances.accepted_at is null
                        """)
                .param("quote", a.quoteId())
                .param("customer", a.customerId())
                .param("booking", a.bookingId())
                .param("intent", a.paymentIntent())
                .param("amount", a.amountCents())
                .param("tax", a.taxCents())
                .update();
    }

    @Override
    public Optional<Acceptance> acceptance(String quoteId) {
        return jdbc.sql("""
                        select quote_id, customer_id, booking_id, payment_intent, amount_cents, tax_cents, accepted_at
                          from booking.quote_acceptances where quote_id = :id
                        """)
                .param("id", quoteId)
                .query((rs, _) -> new Acceptance(
                        rs.getString("quote_id"),
                        rs.getString("customer_id"),
                        rs.getString("booking_id"),
                        rs.getString("payment_intent"),
                        rs.getLong("amount_cents"),
                        rs.getLong("tax_cents"),
                        JdbcTimes.instant(rs, "accepted_at")))
                .optional();
    }

    @Override
    public void markAccepted(String quoteId, Instant at) {
        jdbc.sql("update booking.quote_acceptances set accepted_at = :at where quote_id = :id and accepted_at is null")
                .param("id", quoteId)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private static Request request(ResultSet rs) throws SQLException {
        return new Request(
                rs.getString("id"),
                rs.getLong("number"),
                rs.getString("customer_id"),
                rs.getString("category_id"),
                Objects.requireNonNullElse(rs.getString("title"), "Quote request"),
                rs.getString("description"),
                rs.getString("area"),
                parse(rs.getString("preferred_at")),
                JdbcTimes.requiredInstant(rs, "created_at"),
                JdbcTimes.instant(rs, "respond_by"),
                JdbcTimes.instant(rs, "expires_at"),
                strings(rs.getArray("merchant_ids")));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static @Nullable Instant parse(@Nullable String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException _) {
            return null;
        }
    }
}
