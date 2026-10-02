package ca.northline.booking;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.shared.privacy.PersonalDataContributor.Subject;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: booking's part — a finished job loses its address, details and access notes; a coming one holds. */
class BookingPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "booking";
    }

    private String booking(Subject person, String state, String merchantId) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into booking.bookings (id, customer_id, merchant_id, type, state, starts_at, ends_at, ref,
                                                      title, address_line, details, price_cents)
                        values (:id, :u, :m, 'home', :state, now() - interval '3 days', now() - interval '3 days'
                                + interval '1 hour', :ref, 'Brake inspection', '12 Elm St SW',
                                '{"notes": "dog in yard, gate code 4411"}', 12000)
                        """)
                .param("id", id)
                .param("u", person.userId())
                .param("m", merchantId)
                .param("state", state)
                .param("ref", "BK-" + id.substring(20))
                .update();
        jdbc.sql("""
                        insert into booking.access_notes (booking_id, key_ref, wrapped_key, ciphertext)
                        values (:id, 'local', '\\x00'::bytea, '\\x00'::bytea)
                        """).param("id", id).update();
        return id;
    }

    @Test
    void finishedJobsKeepThePriceButNotTheAddressDetailsOrAccessNotes() {
        var person = person();
        var merchant = data.merchant("provider", "Prairie Wrench");
        var done = booking(person, "signed_off", merchant);
        jdbc.sql("""
                        insert into booking.quote_requests (id, customer_id, category_id, details, media, merchant_ids)
                        values (:id, :u, 'cat', '{"description": "My car at 12 Elm St squeaks"}', '{}', '{}')
                        """).param("id", Ids.next()).param("u", person.userId()).update();

        assertThat(records(person, "booking.bookings")).isEqualTo(1);
        assertThat(section(person, "booking.quoteRequests").orElseThrow().json())
                .contains("squeaks");

        var outcome = erase(person);

        var b = Map.of("id", done);
        assertThat(text(
                        "select concat_ws('|', address_line, details::text, price_cents) from booking.bookings "
                                + "where id = :id",
                        b))
                .isEqualTo("{}|12000");
        assertThat(count("select count(*) from booking.access_notes where booking_id = :id", b))
                .isZero();
        assertThat(text(
                        "select details::text from booking.quote_requests where customer_id = :u",
                        Map.of("u", person.userId())))
                .isEqualTo("{}");
        assertThat(outcome.retained()).containsExactly(new Kept<>("booking.bookings", Retention.TAX_RECORDS));
        assertThat(outcome.held()).isEmpty();
        assertIdempotent(person, outcome);
    }

    @Test
    void aComingJobHoldsTheErasureAndAJobATeamMemberDidStaysTheBusinesss() {
        var person = person();
        var merchant = data.merchant("provider", "Prairie Wrench");
        var coming = booking(person, "confirmed", merchant);
        var technician = person();
        jdbc.sql("update booking.bookings set member_user_id = :t where id = :id")
                .param("t", technician.userId())
                .param("id", coming)
                .update();

        assertThat(erase(person).held()).containsExactly(new Kept<>("booking.bookings", Hold.UPCOMING_BOOKING));
        assertThat(text("select address_line from booking.bookings where id = :id", Map.of("id", coming)))
                .isEqualTo("12 Elm St SW");
        assertThat(records(technician, "booking.assignedJobs")).isEqualTo(1);
        assertThat(erase(technician).retained())
                .contains(new Kept<>("booking.assignedJobs", Retention.BUSINESS_RECORDS));
    }
}
