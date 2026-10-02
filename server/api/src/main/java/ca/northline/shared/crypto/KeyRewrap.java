package ca.northline.shared.crypto;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Re-wraps stored data keys after a key rotation (S-115; docs/runbooks/key-rotation.md § KMS data keys): every
 * {@link SealedColumn} row whose {@code keyRef} isn't the key service's current one gets its data key unwrapped with
 * the old key and wrapped with the current one ({@link SecretSealer#rewrap}); the ciphertext never changes. Runs every
 * {@code KMS_REWRAP_EVERY} (1 h) on every replica — an update only lands while the row still has the old reference, so
 * two replicas never fight — at most {@code batch} rows per column per run. Once {@link #stale()} is all zero the old
 * key (version) can be disabled.
 */
@Slf4j
public class KeyRewrap {

    static final String METRIC = "northline.crypto.rewrapped";

    private final SecretSealer sealer;
    private final JdbcClient jdbc;
    private final List<SealedColumn> columns;
    private final MeterRegistry meters;
    private final int batch;

    KeyRewrap(SecretSealer sealer, JdbcClient jdbc, List<SealedColumn> columns, MeterRegistry meters, int batch) {
        this.sealer = sealer;
        this.jdbc = jdbc;
        this.columns = List.copyOf(columns);
        this.meters = meters;
        this.batch = batch;
    }

    /** What one run did per table. */
    public record Outcome(int rewrapped, int failed) {}

    @Scheduled(
            fixedDelayString = "${northline.crypto.rewrap-every:1h}",
            initialDelayString = "${northline.crypto.rewrap-initial-delay:2m}")
    void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            // e.g. KMS_PROVIDER=local without a key under dev (nothing is sealed there), or the key service is down
            log.warn("Key re-wrap skipped: {}", e.toString());
        }
    }

    /** One pass over every column; returns the outcome per table. */
    public Map<String, Outcome> run() {
        var current = sealer.currentKeyRef();
        var outcomes = new LinkedHashMap<String, Outcome>();
        for (var column : columns) {
            var rewrapped = 0;
            var failed = 0;
            for (var row : staleRows(column, current)) {
                try {
                    var fresh = sealer.rewrap(row.sealed(), column.context(row.id()));
                    rewrapped += replace(column, row, fresh);
                } catch (RuntimeException e) {
                    failed++;
                    log.warn(
                            "Key re-wrap: {} {} (wrapped by {}) failed: {}",
                            column.table(),
                            row.id(),
                            row.sealed().keyRef(),
                            e.toString());
                }
            }
            meters.counter(METRIC, "table", column.table(), "outcome", "rewrapped")
                    .increment(rewrapped);
            meters.counter(METRIC, "table", column.table(), "outcome", "failed").increment(failed);
            if (rewrapped > 0 || failed > 0) {
                log.info(
                        "Key re-wrap: {} — {} re-wrapped with {}, {} failed",
                        column.table(),
                        rewrapped,
                        current,
                        failed);
            }
            outcomes.put(column.table(), new Outcome(rewrapped, failed));
        }
        return outcomes;
    }

    /** Rows per table still wrapped by another key than the current one (0 everywhere = the old key can go). */
    public Map<String, Integer> stale() {
        var current = sealer.currentKeyRef();
        var counts = new LinkedHashMap<String, Integer>();
        for (var c : columns) {
            counts.put(
                    c.table(),
                    jdbc.sql("select count(*) from %s where %s is not null and %s is distinct from :current"
                                    .formatted(c.table(), c.ciphertext(), c.keyRef()))
                            .param("current", current)
                            .query(Integer.class)
                            .single());
        }
        return counts;
    }

    private record Row(String id, SecretSealer.Sealed sealed) {}

    private List<Row> staleRows(SealedColumn c, String current) {
        return jdbc.sql("""
                        select %s as id, %s as key_ref, %s as wrapped_key, %s as ciphertext from %s
                         where %s is not null and %s is distinct from :current
                         order by %s limit :limit""".formatted(
                                c.id(),
                                c.keyRef(),
                                c.wrappedKey(),
                                c.ciphertext(),
                                c.table(),
                                c.ciphertext(),
                                c.keyRef(),
                                c.id()))
                .param("current", current)
                .param("limit", batch)
                .query((rs, _) -> new Row(
                        rs.getString("id"),
                        new SecretSealer.Sealed(
                                rs.getString("key_ref"), rs.getBytes("wrapped_key"), rs.getBytes("ciphertext"))))
                .list();
    }

    /** Only while the row still carries the reference it was read with (a concurrent re-seal or re-wrap wins). */
    private int replace(SealedColumn c, Row row, SecretSealer.Sealed fresh) {
        return jdbc.sql("update %s set %s = :ref, %s = :key where %s = :id and %s = :old"
                        .formatted(c.table(), c.keyRef(), c.wrappedKey(), c.id(), c.keyRef()))
                .param("ref", fresh.keyRef())
                .param("key", fresh.wrappedKey())
                .param("id", row.id())
                .param("old", row.sealed().keyRef())
                .update();
    }
}
