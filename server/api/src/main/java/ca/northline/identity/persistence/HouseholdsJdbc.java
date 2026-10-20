package ca.northline.identity.persistence;

import ca.northline.identity.api.Households;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link Households} over {@code identity.households} and {@code household_members} (S-152). */
@Repository
@RequiredArgsConstructor
class HouseholdsJdbc implements Households {

    private final JdbcClient jdbc;

    @Override
    public String ensure(String userId) {
        var existing = jdbc.sql("""
                        select household_id from identity.household_members where user_id = :u
                         order by role = 'owner' desc, household_id limit 1
                        """).param("u", userId).query(String.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        var id = Ids.next();
        jdbc.sql("insert into identity.households (id, name, plus_plan) values (:id, 'Home', 'none')")
                .param("id", id)
                .update();
        jdbc.sql("insert into identity.household_members (household_id, user_id, role) values (:h, :u, 'owner')")
                .param("h", id)
                .param("u", userId)
                .update();
        return id;
    }

    @Override
    public boolean shared(String userId, String otherUserId) {
        return jdbc.sql("""
                        select exists (select 1 from identity.household_members a
                                         join identity.household_members b on b.household_id = a.household_id
                                        where a.user_id = :a and b.user_id = :b)
                        """)
                .param("a", userId)
                .param("b", otherUserId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public void mirrorPlus(
            String householdId,
            String plan,
            @Nullable Instant since,
            @Nullable Instant renewsAt,
            @Nullable String subscriptionId) {
        jdbc.sql("""
                        update identity.households
                           set plus_plan = :plan, plus_since = :since, renews_at = :renews,
                               stripe_subscription_id = :sub
                         where id = :h
                        """)
                .param("h", householdId)
                .param("plan", plan)
                .param("since", JdbcTimes.ts(since))
                .param("renews", JdbcTimes.ts(renewsAt))
                .param("sub", subscriptionId)
                .update();
    }
}
