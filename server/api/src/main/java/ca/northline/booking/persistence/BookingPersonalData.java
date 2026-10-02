package ca.northline.booking.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, booking: the person's bookings, quote requests and quote acceptances (as a customer), and the jobs they were
 * assigned to (as a team member).
 *
 * <p>Erasure: a finished booking keeps its price, tax, dates and service (sales records) under the person's id; its
 * street address, job details and the sealed access notes go, and so do the job descriptions of quote requests. A
 * booking still to happen holds the erasure. Jobs a team member did stay the business's records (their id only).
 */
@Component
@RequiredArgsConstructor
class BookingPersonalData implements PersonalDataContributor {

    static final String UPCOMING = "('requested', 'confirmed', 'en_route', 'on_site')";

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "booking";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(jdbc, "booking.bookings", "Bookings", "Réservations", """
                        select id, ref, merchant_id, service_id, title, type, state, starts_at, ends_at, address_line,
                               area, details, price_cents, deposit_cents, tax_cents, created_at
                          from booking.bookings where customer_id = :u order by created_at
                        """, p),
                section(jdbc, "booking.quoteRequests", "Quote requests", "Demandes de soumission", """
                        select id, number, category_id, details, merchant_ids, created_at, respond_by, expires_at
                          from booking.quote_requests where customer_id = :u order by created_at
                        """, p),
                section(jdbc, "booking.quoteAcceptances", "Accepted quotes", "Soumissions acceptées", """
                        select quote_id, booking_id, amount_cents, tax_cents, started_at, accepted_at
                          from booking.quote_acceptances where customer_id = :u
                        """, p),
                section(
                        jdbc,
                        "booking.assignedJobs",
                        "Jobs assigned to you",
                        "Travaux qui vous ont été confiés",
                        """
                        select id, ref, merchant_id, title, state, starts_at, ends_at
                          from booking.bookings where member_user_id = :u order by starts_at
                        """,
                        p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        var upcoming = jdbc.sql("select count(*) from booking.bookings where customer_id = :u and state in " + UPCOMING)
                .param("u", u)
                .query(Integer.class)
                .single();
        jdbc.sql("""
                        delete from booking.access_notes n using booking.bookings b
                         where n.booking_id = b.id and b.customer_id = :u and b.state not in
                        """ + UPCOMING).param("u", u).update();
        jdbc.sql("""
                        update booking.bookings
                           set address_line = null, details = '{}'::jsonb, updated_at = now(), version = version + 1
                         where customer_id = :u and state not in
                        """ + UPCOMING + " and (address_line is not null or details <> '{}'::jsonb)")
                .param("u", u)
                .update();
        jdbc.sql("""
                        update booking.quote_requests set details = '{}'::jsonb
                         where customer_id = :u and details <> '{}'::jsonb
                        """).param("u", u).update();
        var assigned = jdbc.sql("select exists (select 1 from booking.bookings where member_user_id = :u)")
                .param("u", u)
                .query(Boolean.class)
                .single();
        var outcome = Erasure.done().retaining("booking.bookings", Retention.TAX_RECORDS);
        if (assigned) {
            outcome = outcome.retaining("booking.assignedJobs", Retention.BUSINESS_RECORDS);
        }
        return upcoming > 0 ? outcome.holding("booking.bookings", Hold.UPCOMING_BOOKING) : outcome;
    }
}
