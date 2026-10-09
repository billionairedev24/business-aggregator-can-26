package ca.northline.booking.persistence;

import ca.northline.booking.application.VisitStore;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link VisitStore} over {@code booking.bookings} (V364's job site). */
@Repository
@RequiredArgsConstructor
class VisitJdbc implements VisitStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<Visit> visit(String bookingId) {
        return jdbc.sql("""
                        select id, merchant_id, member_user_id, customer_id, state, site_lat, site_lng
                          from booking.bookings where id = :id""")
                .param("id", bookingId)
                .query((rs, _) -> new Visit(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("member_user_id"),
                        rs.getString("customer_id"),
                        rs.getString("state"),
                        rs.getObject("site_lat", Double.class),
                        rs.getObject("site_lng", Double.class)))
                .optional();
    }
}
