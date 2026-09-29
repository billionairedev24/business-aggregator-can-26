package ca.northline.payments.persistence;

import ca.northline.payments.application.IdempotencyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency keys in {@code payments.idempotency_keys} — the {@code local} and {@code test} stand-in for Redis (which
 * isn't running there). Claims commit in their own transaction so a concurrent duplicate sees them at once.
 */
@Repository
@Profile({"local", "test"})
@RequiredArgsConstructor
class PaymentsIdempotencyJdbcStore implements IdempotencyStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Stored> claim(String scope, String key, String fingerprint, Duration ttl) {
        var now = clock.instant();
        jdbc.sql("delete from payments.idempotency_keys where scope = :s and key = :k and expires_at <= :now")
                .param("s", scope)
                .param("k", key)
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .update();
        var inserted = jdbc.sql("""
                        insert into payments.idempotency_keys (scope, key, fingerprint, created_at, expires_at)
                        values (:s, :k, :f, :now, :expires) on conflict do nothing""")
                .param("s", scope)
                .param("k", key)
                .param("f", fingerprint)
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .param("expires", now.plus(ttl).atOffset(java.time.ZoneOffset.UTC))
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        return jdbc.sql("select fingerprint, status, body from payments.idempotency_keys where scope = :s and key = :k")
                .param("s", scope)
                .param("k", key)
                .query((rs, _) -> new Stored(
                        rs.getString("fingerprint"), rs.getObject("status", Integer.class), rs.getString("body")))
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String scope, String key, int status, String body) {
        jdbc.sql("update payments.idempotency_keys set status = :status, body = :body where scope = :s and key = :k")
                .param("status", status)
                .param("body", body)
                .param("s", scope)
                .param("k", key)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String scope, String key) {
        jdbc.sql("delete from payments.idempotency_keys where scope = :s and key = :k and status is null")
                .param("s", scope)
                .param("k", key)
                .update();
    }
}
