package ca.northline.booking.persistence;

import ca.northline.booking.api.CustomerBookings.CustomerBooking;
import ca.northline.booking.api.CustomerBookings.NewBooking;
import ca.northline.booking.application.CustomerBookingStore;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link CustomerBookingStore} over {@code booking.bookings} and {@code booking.access_notes} (V115). */
@Repository
@RequiredArgsConstructor
class CustomerBookingJdbc implements CustomerBookingStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    @Override
    public boolean lockAndCheckOverlap(String memberUserId, Instant from, Instant to) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('booking.member:' || :member, 0))")
                .param("member", memberUserId)
                .query((rs, _) -> rs.getObject(1))
                .list();
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from booking.bookings
                                        where member_user_id = :member and state <> 'cancelled'
                                          and starts_at < :to and coalesce(ends_at, starts_at) > :from)
                        """)
                .param("member", memberUserId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query(Boolean.class)
                .single());
    }

    @Override
    public String insert(NewBooking b, Instant at) {
        var ref = "BK-"
                + jdbc.sql("select nextval('booking.booking_ref_seq')")
                        .query(Long.class)
                        .single();
        jdbc.sql("""
                        insert into booking.bookings (id, ref, customer_id, merchant_id, member_user_id, service_id, quote_id,
                               type, state, starts_at, ends_at, title, address_line, area, details, escrow_id, price_cents,
                               deposit_cents, tax_cents, source, free_cancel_until, created_at, updated_at)
                        values (:id, :ref, :customer, :merchant, :member, :service, :quote, :type, 'confirmed', :starts,
                                :ends, :title, :address, :area, cast(:details as jsonb), :escrow, :price, :deposit, :tax,
                                'customer', :freeCancel, :at, :at)
                        """)
                .param("id", b.bookingId())
                .param("ref", ref)
                .param("customer", b.customerId())
                .param("merchant", b.merchantId())
                .param("member", b.memberUserId())
                .param("service", b.serviceId(), Types.VARCHAR)
                .param("quote", b.quoteId(), Types.VARCHAR)
                .param("type", b.type())
                .param("starts", JdbcTimes.ts(b.startsAt()))
                .param("ends", JdbcTimes.ts(b.endsAt()))
                .param("title", b.title())
                .param("address", b.addressLine(), Types.VARCHAR)
                .param("area", b.area(), Types.VARCHAR)
                .param("details", JSON.writeValueAsString(b.details()))
                .param("escrow", b.escrowId(), Types.VARCHAR)
                .param("price", b.priceCents())
                .param("deposit", b.depositCents())
                .param("tax", b.taxCents())
                .param(
                        "freeCancel",
                        b.freeCancelUntil() == null ? null : JdbcTimes.ts(b.freeCancelUntil()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("at", JdbcTimes.ts(at))
                .update();
        return ref;
    }

    @Override
    public void sealAccess(String bookingId, Sealed note) {
        jdbc.sql("""
                        insert into booking.access_notes (booking_id, key_ref, wrapped_key, ciphertext)
                        values (:id, :keyRef, :wrapped, :ciphertext)
                        """)
                .param("id", bookingId)
                .param("keyRef", note.keyRef())
                .param("wrapped", note.wrappedKey())
                .param("ciphertext", note.ciphertext())
                .update();
    }

    @Override
    public Optional<Sealed> accessNote(String bookingId) {
        return jdbc.sql("select key_ref, wrapped_key, ciphertext from booking.access_notes where booking_id = :id")
                .param("id", bookingId)
                .query((rs, _) -> new Sealed(rs.getString(1), rs.getBytes(2), rs.getBytes(3)))
                .optional();
    }

    @Override
    public Optional<CustomerBooking> find(String bookingId) {
        return jdbc.sql("""
                        select id, ref, merchant_id, member_user_id, state, coalesce(title, 'Job') as title,
                               coalesce(type, 'visit') as type, starts_at, coalesce(ends_at, starts_at) as ends_at,
                               address_line, coalesce(price_cents, 0) as price, coalesce(deposit_cents, 0) as deposit,
                               coalesce(tax_cents, 0) as tax, escrow_id is not null as paid, free_cancel_until
                          from booking.bookings where id = :id
                        """)
                .param("id", bookingId)
                .query((rs, _) -> new CustomerBooking(
                        rs.getString("id"),
                        Objects.requireNonNullElse(rs.getString("ref"), ""),
                        rs.getString("merchant_id"),
                        rs.getString("member_user_id"),
                        rs.getString("state"),
                        rs.getString("title"),
                        rs.getString("type"),
                        JdbcTimes.requiredInstant(rs, "starts_at"),
                        JdbcTimes.requiredInstant(rs, "ends_at"),
                        rs.getString("address_line"),
                        rs.getLong("price"),
                        rs.getLong("deposit"),
                        rs.getLong("tax"),
                        rs.getBoolean("paid"),
                        JdbcTimes.instant(rs, "free_cancel_until")))
                .optional();
    }

    @Override
    public Optional<String> customerOf(String bookingId) {
        return jdbc.sql("select customer_id from booking.bookings where id = :id")
                .param("id", bookingId)
                .query(String.class)
                .optional();
    }
}
