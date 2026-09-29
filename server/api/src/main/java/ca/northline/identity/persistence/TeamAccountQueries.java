package ca.northline.identity.persistence;

import ca.northline.identity.api.TeamAccounts;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link TeamAccounts} over {@code identity.users} (settings &amp; compliance workstream). */
@Repository
@RequiredArgsConstructor
class TeamAccountQueries implements TeamAccounts {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Account> accounts(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select id,
                               coalesce(nullif(trim(concat_ws(' ', first_name, last_name)), ''), display_name, '') as name,
                               email::text as email, phone, mfa_primary
                          from identity.users where id in (:ids)
                        """)
                .param("ids", userIds.stream().distinct().toList())
                .query((rs, _) -> new Account(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("mfa_primary")))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Account::id, Function.identity()));
    }
}
