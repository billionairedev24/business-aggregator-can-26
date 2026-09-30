package ca.northline.worker.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.search.*} (docs/runbooks/search.md).
 *
 * @param reconcile the sweep of rows changed without an event
 */
@ConfigurationProperties("northline.search")
public record SearchProperties(@DefaultValue Reconcile reconcile) {

    /**
     * @param enabled run the sweep (every replica tries; one holds the lease at a time)
     * @param every pause between two sweeps
     * @param overlap how far back each sweep looks before the last watermark (transactions still committing)
     * @param batch merchants refreshed per sweep at most (the rest wait for the next one)
     * @param lease how long a replica owns the sweep before another may take over
     */
    public record Reconcile(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1m") Duration every,
            @DefaultValue("2m") Duration overlap,
            @DefaultValue("500") int batch,
            @DefaultValue("5m") Duration lease) {}
}
