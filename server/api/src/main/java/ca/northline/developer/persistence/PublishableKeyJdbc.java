package ca.northline.developer.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.developer.application.PublishableKeyStore;
import ca.northline.developer.domain.PublishableKey;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PublishableKeyStore} over {@code developer.publishable_keys} (V183). */
@Repository
@RequiredArgsConstructor
class PublishableKeyJdbc implements PublishableKeyStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<PublishableKey> active(String merchantId) {
        return jdbc.sql("select * from developer.publishable_keys where merchant_id = :m and revoked_at is null")
                .param("m", merchantId)
                .query((rs, _) -> key(rs))
                .optional();
    }

    @Override
    public Optional<PublishableKey> byKey(String key) {
        return jdbc.sql("select * from developer.publishable_keys where key = :k and revoked_at is null")
                .param("k", key)
                .query((rs, _) -> key(rs))
                .optional();
    }

    @Override
    public void revoke(String id, Instant at) {
        jdbc.sql("update developer.publishable_keys set revoked_at = :at where id = :id and revoked_at is null")
                .param("id", id)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void insert(PublishableKey key, String createdBy) {
        jdbc.sql("""
                        insert into developer.publishable_keys (id, merchant_id, key, allowed_origins, created_by, created_at)
                        values (:id, :m, :key, cast(:origins as text[]), :by, :at)
                        """)
                .param("id", key.id())
                .param("m", key.merchantId())
                .param("key", key.key())
                .param("origins", pgArray(key.allowedOrigins()))
                .param("by", createdBy)
                .param("at", ts(key.createdAt()))
                .update();
    }

    @Override
    public void origins(String id, List<String> allowedOrigins) {
        jdbc.sql("update developer.publishable_keys set allowed_origins = cast(:origins as text[]) where id = :id")
                .param("id", id)
                .param("origins", pgArray(allowedOrigins))
                .update();
    }

    private static PublishableKey key(ResultSet rs) throws SQLException {
        return new PublishableKey(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("key"),
                strings(rs.getArray("allowed_origins")),
                requiredInstant(rs, "created_at"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }

    private static String pgArray(List<String> values) {
        return values.stream()
                .map(v -> '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"')
                .collect(Collectors.joining(",", "{", "}"));
    }
}
