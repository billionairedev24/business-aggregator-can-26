package ca.northline.identity.persistence;

import ca.northline.identity.api.MarketAudience;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link MarketAudience} over {@code identity.addresses} and {@code identity.users} (active accounts only). */
@Repository
@RequiredArgsConstructor
class MarketAudienceQueries implements MarketAudience {

    private static final int CHUNK = 5_000;

    private final JdbcClient jdbc;

    @Override
    public Set<String> inProvince(Collection<String> userIds, String province) {
        var ids = List.copyOf(new HashSet<>(userIds));
        var found = new HashSet<String>();
        for (var from = 0; from < ids.size(); from += CHUNK) {
            found.addAll(jdbc.sql("""
                            select distinct a.user_id from identity.addresses a
                              join identity.users u on u.id = a.user_id
                             where a.user_id in (:ids) and a.province = :province
                               and coalesce(u.status, 'active') = 'active'""")
                    .param("ids", ids.subList(from, Math.min(ids.size(), from + CHUNK)))
                    .param("province", province)
                    .query((rs, _) -> rs.getString(1))
                    .list());
        }
        return Set.copyOf(found);
    }
}
