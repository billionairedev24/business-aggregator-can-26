package ca.northline.payments.persistence;

import ca.northline.booking.api.JobEscrows;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link JobEscrows} over {@code payments.escrows}: the same figures as the Earnings ledger's rows. */
@Repository
@RequiredArgsConstructor
class JobEscrowQueries implements JobEscrows {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Held> of(String merchantId, Collection<String> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        record Row(String bookingId, Held held) {}
        return jdbc
                .sql("""
                        select ref_id, coalesce(state, 'held') as state, coalesce(amount_cents, 0) as amount,
                               coalesce(tax_cents, 0) as tax, coalesce(fee_cents, 0) as fee
                          from payments.escrows
                         where merchant_id = :m and ref_type = 'booking' and ref_id in (:ids)
                        """)
                .param("m", merchantId)
                .param("ids", List.copyOf(bookingIds))
                .query((rs, _) -> {
                    var amount = rs.getLong("amount");
                    var tax = rs.getLong("tax");
                    var fee = rs.getLong("fee");
                    return new Row(
                            rs.getString("ref_id"),
                            new Held(rs.getString("state"), amount + tax, tax, fee, amount - fee));
                })
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Row::bookingId, Row::held, (a, _) -> a));
    }
}
