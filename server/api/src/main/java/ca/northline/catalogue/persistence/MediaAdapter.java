package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.array;
import static ca.northline.catalogue.persistence.Sql.intOrNull;
import static ca.northline.catalogue.persistence.Sql.longOrNull;

import ca.northline.catalogue.application.MediaRepository;
import ca.northline.catalogue.domain.MediaAsset;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code catalogue.media}. The 64-bit average hash is stored big-endian in {@code phash bytea}; near-duplicates are
 * found by Hamming distance ({@code bit_count} over the XOR of the two hashes).
 */
@Repository
@RequiredArgsConstructor
class MediaAdapter implements MediaRepository {

    private final JdbcClient jdbc;

    @Override
    public void insert(MediaAsset a) {
        jdbc.sql("""
                        insert into catalogue.media (id, owner_type, owner_id, url, phash, exif_ok, kind, merchant_id,
                          content_type, width, height, byte_size, on_white)
                        values (:id, null, null, :key, :phash, true, 'gallery', :merchant, :type, :w, :h, :size, :white)
                        """)
                .param("id", a.id())
                .param("key", a.storageKey())
                .param("phash", a.phash() == null ? null : toBytes(a.phash()))
                .param("merchant", a.merchantId())
                .param("type", a.contentType())
                .param("w", a.width())
                .param("h", a.height())
                .param("size", a.byteSize())
                .param("white", a.onWhite())
                .update();
    }

    @Override
    public Optional<MediaAsset> find(String id) {
        return jdbc.sql("select * from catalogue.media where id = :id")
                .param("id", id)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public List<MediaAsset> findAll(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        var byId = jdbc
                .sql("select * from catalogue.media where id = any(:ids)")
                .param("ids", array(ids))
                .query((rs, _) -> map(rs))
                .list()
                .stream()
                .collect(Collectors.toMap(MediaAsset::id, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Override
    public boolean hasNearDuplicate(long phash, String exceptMerchantId, int maxDistance) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from catalogue.media
                                        where phash is not null and octet_length(phash) = 8
                                          and coalesce(merchant_id, '') <> :m
                                          and bit_count(('x' || encode(phash, 'hex'))::bit(64)
                                                        # ('x' || :hex)::bit(64)) <= :d)
                        """)
                .param("m", exceptMerchantId)
                .param("hex", "%016x".formatted(phash))
                .param("d", maxDistance)
                .query(Boolean.class)
                .single());
    }

    @Override
    public void attach(Collection<String> ids, String ownerType, String ownerId) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        update catalogue.media set owner_type = :type, owner_id = :owner,
                          kind = case when id = :main then 'main' else 'gallery' end
                         where id = any(:ids) and (owner_id is null or owner_id = :owner)
                        """)
                .param("type", ownerType)
                .param("owner", ownerId)
                .param("main", ids.iterator().next())
                .param("ids", array(ids))
                .update();
    }

    private static MediaAsset map(ResultSet rs) throws SQLException {
        var hash = rs.getBytes("phash");
        var width = intOrNull(rs, "width");
        var height = intOrNull(rs, "height");
        var size = longOrNull(rs, "byte_size");
        return new MediaAsset(
                rs.getString("id"),
                rs.getString("merchant_id"),
                Objects.requireNonNullElse(rs.getString("url"), ""),
                Objects.requireNonNullElse(rs.getString("content_type"), "image/jpeg"),
                width == null ? 0 : width,
                height == null ? 0 : height,
                size == null ? 0 : size,
                rs.getBoolean("on_white"),
                fromBytes(hash));
    }

    private static byte[] toBytes(long hash) {
        return ByteBuffer.allocate(Long.BYTES).putLong(hash).array();
    }

    private static @Nullable Long fromBytes(byte @Nullable [] bytes) {
        return bytes == null || bytes.length != Long.BYTES
                ? null
                : ByteBuffer.wrap(bytes).getLong();
    }
}
