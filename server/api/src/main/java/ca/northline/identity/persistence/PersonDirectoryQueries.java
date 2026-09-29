package ca.northline.identity.persistence;

import ca.northline.identity.api.PersonDirectory;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Minimal {@link PersonDirectory} over {@code identity.users}, added by the operations workstream (see
 * docs/DECISIONS.md "Operations"); the identity workstream may replace it.
 */
@Repository
@RequiredArgsConstructor
class PersonDirectoryQueries implements PersonDirectory {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Person> people(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select id, coalesce(display_name, '') as display_name, reliability_score
                          from identity.users where id in (:ids)
                        """)
                .param("ids", userIds.stream().distinct().toList())
                .query((rs, _) -> new Person(
                        rs.getString("id"), rs.getString("display_name"), rs.getBigDecimal("reliability_score")))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Person::id, Function.identity()));
    }
}
