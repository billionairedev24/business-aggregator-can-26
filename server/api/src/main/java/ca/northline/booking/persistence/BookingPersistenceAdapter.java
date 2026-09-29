package ca.northline.booking.persistence;

import ca.northline.booking.application.BookingRepository;
import ca.northline.booking.domain.Approval;
import ca.northline.booking.domain.Booking;
import ca.northline.booking.domain.BookingLogEntry;
import ca.northline.shared.JdbcTimes;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class BookingPersistenceAdapter implements BookingRepository {

    private final BookingRowRepository rows;
    private final BookingRowMapper mapper;
    private final JdbcClient jdbc;

    @Override
    public Optional<Booking> find(String merchantId, String bookingId) {
        return rows.findByIdAndMerchantId(bookingId, merchantId).map(mapper::toDomain);
    }

    @Override
    public void save(Booking booking) {
        rows.save(mapper.toRow(booking));
    }

    @Override
    public void append(List<BookingLogEntry> entries) {
        for (var e : entries) {
            var point = e.point();
            jdbc.sql("""
                            insert into booking.booking_events (id, booking_id, type, at, actor_id, geom, media_id, note)
                            values (:id, :bookingId, :type, :at, :actorId,
                                    case when cast(:lat as double precision) is null then null
                                         else ST_SetSRID(ST_MakePoint(cast(:lng as double precision),
                                                                      cast(:lat as double precision)), 4326)::geography end,
                                    :mediaId, :note)
                            """)
                    .param("id", e.id())
                    .param("bookingId", e.bookingId())
                    .param("type", e.type())
                    .param("at", JdbcTimes.ts(e.at()))
                    .param("actorId", e.actorId())
                    .param("lat", point == null ? null : point.lat(), java.sql.Types.DOUBLE)
                    .param("lng", point == null ? null : point.lng(), java.sql.Types.DOUBLE)
                    .param("mediaId", e.mediaId(), java.sql.Types.VARCHAR)
                    .param("note", e.note(), java.sql.Types.VARCHAR)
                    .update();
        }
    }

    @Override
    public void insert(Approval approval) {
        jdbc.sql("""
                        insert into booking.approvals (id, booking_id, amount_cents, description, state, requested_at, requested_by)
                        values (:id, :bookingId, :amount, :description, :state, :at, :by)
                        """)
                .param("id", approval.id())
                .param("bookingId", approval.bookingId())
                .param("amount", approval.amountCents())
                .param("description", approval.description())
                .param("state", approval.state().code())
                .param("at", JdbcTimes.ts(approval.requestedAt()))
                .param("by", approval.requestedBy())
                .update();
    }
}
