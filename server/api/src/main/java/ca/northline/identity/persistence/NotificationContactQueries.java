package ca.northline.identity.persistence;

import ca.northline.identity.api.NotificationContacts;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link NotificationContacts} over {@code identity.users} (active accounts only). */
@Repository
@RequiredArgsConstructor
class NotificationContactQueries implements NotificationContacts {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Contact> contacts(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select id,
                               coalesce(nullif(trim(concat_ws(' ', first_name, last_name)), ''), display_name, '') as name,
                               email::text as email, phone, locale
                          from identity.users
                         where id in (:ids) and coalesce(status, 'active') = 'active'
                        """)
                .param("ids", userIds.stream().distinct().toList())
                .query((rs, _) -> {
                    var locale = rs.getString("locale");
                    return new Contact(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("email"),
                            rs.getString("phone"),
                            Locale.forLanguageTag(locale == null || locale.isBlank() ? "en-CA" : locale.strip()));
                })
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Contact::id, Function.identity()));
    }
}
