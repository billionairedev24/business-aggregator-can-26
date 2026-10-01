package ca.northline.identity.persistence;

import ca.northline.identity.api.PlusMemberships;
import ca.northline.shared.JdbcTimes;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link PlusMemberships} over {@code identity.households} and {@code household_members}. A plan whose renewal date has
 * passed is over (a free trial that was never paid for, S-59).
 */
@Repository
@RequiredArgsConstructor
class PlusMembershipQueries implements PlusMemberships {

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public Optional<Plus> of(String userId) {
        return jdbc.sql("""
                        select h.id, h.plus_plan, h.plus_since, h.renews_at,
                               (select count(*) from identity.household_members x where x.household_id = h.id) as members
                          from identity.household_members m
                          join identity.households h on h.id = m.household_id
                         where m.user_id = :u and h.plus_plan in ('monthly', 'annual')
                           and (h.renews_at is null or h.renews_at > :now)
                         order by h.renews_at desc nulls last
                         limit 1
                        """)
                .param("u", userId)
                .param("now", JdbcTimes.ts(clock.instant()))
                .query((rs, _) -> new Plus(
                        rs.getString("id"),
                        rs.getString("plus_plan"),
                        JdbcTimes.instant(rs, "plus_since"),
                        JdbcTimes.instant(rs, "renews_at"),
                        rs.getInt("members")))
                .optional();
    }
}
