package ca.northline.booking.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.booking.application.CustomerPhotoStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CustomerPhotoStore} over {@code booking.customer_photos} (V406). */
@Repository
@RequiredArgsConstructor
class CustomerPhotoJdbc implements CustomerPhotoStore {

    private static final String COLUMNS =
            "p.id, p.customer_id, p.storage_key, p.content_type, p.byte_size, p.file_name, p.ref_type, p.ref_id,"
                    + " p.created_at";

    private final JdbcClient jdbc;

    @Override
    public void insert(StoredPhoto p) {
        jdbc.sql("""
                        insert into booking.customer_photos (id, customer_id, storage_key, content_type, byte_size,
                                                             file_name, created_at)
                        values (:id, :c, :key, :type, :size, :name, :at)
                        """)
                .param("id", p.id())
                .param("c", p.customerId())
                .param("key", p.storageKey())
                .param("type", p.contentType())
                .param("size", p.byteSize())
                .param("name", p.fileName())
                .param("at", ts(p.createdAt()))
                .update();
    }

    @Override
    public Optional<StoredPhoto> own(String customerId, String photoId) {
        return jdbc.sql("select " + COLUMNS + " from booking.customer_photos p where p.id = :id and p.customer_id = :c")
                .param("id", photoId)
                .param("c", customerId)
                .query((rs, _) -> photo(rs))
                .optional();
    }

    @Override
    public List<StoredPhoto> attachable(String customerId, Collection<String> ids) {
        return jdbc.sql("select " + COLUMNS + """
                         from booking.customer_photos p
                        where p.customer_id = :c and p.id in (:ids) and p.ref_id is null""")
                .param("c", customerId)
                .param("ids", ids)
                .query((rs, _) -> photo(rs))
                .list();
    }

    @Override
    public void attach(String customerId, Collection<String> ids, String refType, String refId) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        update booking.customer_photos set ref_type = :type, ref_id = :ref
                         where customer_id = :c and id in (:ids) and ref_id is null""")
                .param("type", refType)
                .param("ref", refId)
                .param("c", customerId)
                .param("ids", ids)
                .update();
    }

    @Override
    public List<StoredPhoto> of(String refType, String refId) {
        return jdbc.sql("select " + COLUMNS + """
                         from booking.customer_photos p
                        where p.ref_type = :type and p.ref_id = :ref
                        order by p.created_at, p.id""")
                .param("type", refType)
                .param("ref", refId)
                .query((rs, _) -> photo(rs))
                .list();
    }

    @Override
    public Optional<StoredPhoto> visibleTo(String merchantId, String photoId) {
        return jdbc.sql("select " + COLUMNS + """
                         from booking.customer_photos p
                         left join booking.quote_requests r on p.ref_type = 'quote_request' and r.id = p.ref_id
                         left join booking.bookings b on p.ref_type = 'booking' and b.id = p.ref_id
                        where p.id = :id
                          and ((r.id is not null and :m = any(r.merchant_ids)) or b.merchant_id = :m)""")
                .param("id", photoId)
                .param("m", merchantId)
                .query((rs, _) -> photo(rs))
                .optional();
    }

    private static StoredPhoto photo(ResultSet rs) throws SQLException {
        return new StoredPhoto(
                rs.getString("id"),
                rs.getString("customer_id"),
                rs.getString("storage_key"),
                rs.getString("content_type"),
                rs.getLong("byte_size"),
                rs.getString("file_name"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                requiredInstant(rs, "created_at"));
    }
}
