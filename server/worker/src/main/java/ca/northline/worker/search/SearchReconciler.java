package ca.northline.worker.search;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The safety net under the event-driven indexer (S-43): every minute, the merchants whose rows changed since the last
 * sweep ({@code updated_at} of services, offers, catalogue records, dishes, menus, kitchen settings and hours, the
 * merchant, its page and location, weekly availability; reviews written, replied or reported) are refreshed whole.
 * That covers the edits no event announces — a price or a name changed on a live listing, new hours, a new review —
 * within about a minute.
 *
 * <p>The watermark lives in {@code search.sync_state} ({@code reconcile}). Replicas share it through a lease: a sweep
 * starts only when it can move the row's {@code updated_at} forward past the lease, so one replica sweeps at a time and
 * another takes over if it dies. Each merchant is refreshed in its own transaction; a sweep looks {@code overlap}
 * back before the watermark so rows of transactions that committed late are not missed (refreshing twice is harmless).
 */
@Slf4j
public class SearchReconciler {

    static final String NAME = "reconcile";

    private final JdbcClient jdbc;
    private final DocumentSource source;
    private final SearchProjection projection;
    private final TransactionOperations transactions;
    private final SearchProperties.Reconcile settings;

    SearchReconciler(
            JdbcClient jdbc,
            DocumentSource source,
            SearchProjection projection,
            TransactionOperations transactions,
            SearchProperties.Reconcile settings) {
        this.jdbc = jdbc;
        this.source = source;
        this.projection = projection;
        this.transactions = transactions;
        this.settings = settings;
    }

    @Scheduled(
            fixedDelayString = "${northline.search.reconcile.every:1m}",
            initialDelayString = "${northline.search.reconcile.initial-delay:1m}")
    void scheduled() {
        if (settings.enabled()) {
            try {
                sweep();
            } catch (RuntimeException e) {
                log.warn("Search reconcile failed, next try in {}: {}", settings.every(), e.toString());
            }
        }
    }

    /** One sweep; returns how many merchants were refreshed (0 when another replica holds the lease). */
    public int sweep() {
        var watermark = lease();
        if (watermark.isEmpty()) {
            return 0;
        }
        var startedAt = jdbc.sql("select clock_timestamp()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        var changed = source.changedSince(watermark.get().minus(settings.overlap()), settings.batch());
        for (var merchant : changed) {
            transactions.executeWithoutResult(
                    _ -> projection.refresh(new Scope.Merchant(merchant.merchantId()), SearchProjection.Targets.LIVE));
        }
        // a full batch leaves the rest for the next sweep: the watermark stops at the last merchant's change
        var next = changed.size() < settings.batch()
                ? startedAt
                : changed.getLast().changedAt();
        // and the lease is given back: any replica may run the next sweep
        jdbc.sql("""
                        update search.sync_state set watermark = :w, updated_at = clock_timestamp() - interval '1 day'
                         where name = :n""")
                .param("w", OffsetDateTime.ofInstant(next, ZoneOffset.UTC))
                .param("n", NAME)
                .update();
        if (!changed.isEmpty()) {
            log.info("Search reconcile: {} merchant(s) refreshed, watermark {}", changed.size(), next);
        }
        return changed.size();
    }

    /** Takes the lease and returns the watermark; empty when another replica holds it. The first sweep starts now. */
    private Optional<Instant> lease() {
        jdbc.sql("""
                        insert into search.sync_state (name, watermark, updated_at)
                        values (:n, clock_timestamp(), clock_timestamp() - interval '1 day') on conflict do nothing""").param("n", NAME).update();
        return jdbc.sql("""
                        update search.sync_state set updated_at = clock_timestamp()
                         where name = :n and updated_at < clock_timestamp() - cast(:lease as interval)
                        returning watermark""")
                .param("n", NAME)
                .param("lease", settings.lease().toSeconds() + " seconds")
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }
}
