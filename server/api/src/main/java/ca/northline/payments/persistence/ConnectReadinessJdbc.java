package ca.northline.payments.persistence;

import ca.northline.payments.api.ConnectReadiness;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ConnectReadiness} over {@code payments.connected_accounts} (V060, webhook columns V063). */
@Repository
@RequiredArgsConstructor
class ConnectReadinessJdbc implements ConnectReadiness {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Account> of(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select merchant_id, stripe_account, charges_enabled, payouts_enabled, requirements_due,
                               requirements_past_due, disabled_reason
                          from payments.connected_accounts where merchant_id = any(:m)
                        """)
                .param("m", merchantIds.toArray(String[]::new))
                .query((rs, _) -> Map.entry(
                        rs.getString("merchant_id"),
                        new Account(
                                rs.getString("stripe_account"),
                                rs.getObject("charges_enabled", Boolean.class),
                                rs.getObject("payouts_enabled", Boolean.class),
                                rs.getInt("requirements_due"),
                                rs.getInt("requirements_past_due"),
                                rs.getString("disabled_reason"))))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
