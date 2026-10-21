package ca.northline.payments.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.plus.billing.*} (S-162, docs/runbooks/loyalty.md § Plus billing). The plans' prices and rules are
 * loyalty's plan settings ({@code northline.plus.*}); this is how Northline collects them.
 *
 * @param retryDays {@code PLUS_RETRY_DAYS}: a refused renewal is tried again this many days after it was due (each
 *     later than the one before); after the last one the membership lapses
 * @param unconfirmed {@code PLUS_UNCONFIRMED_AFTER}: a first payment the member never confirmed with their bank is
 *     dropped after this long (the membership never starts)
 * @param batch periods the renewal job handles per run
 */
@ConfigurationProperties("northline.plus.billing")
public record PlusBillingSettings(
        @DefaultValue({"1", "3", "5"}) List<Integer> retryDays,
        @DefaultValue("PT24H") Duration unconfirmed,
        @DefaultValue("200") int batch) {

    public PlusBillingSettings {
        retryDays = List.copyOf(retryDays);
        if (retryDays.stream().anyMatch(d -> d < 1 || d > 30) || unconfirmed.isNegative() || batch < 1) {
            throw new IllegalArgumentException(
                    "northline.plus.billing: retry-days 1–30, each later than the one before; unconfirmed ≥ 0; batch ≥ 1");
        }
    }
}
