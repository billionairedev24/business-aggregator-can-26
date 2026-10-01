package ca.northline.account.persistence;

import ca.northline.account.application.Favourites.FavouriteStore;
import ca.northline.account.application.Favourites.Stored;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link FavouriteStore} over {@code account.favourites} (V160). */
@Repository
@RequiredArgsConstructor
class FavouritesJdbc implements FavouriteStore {

    private final JdbcClient jdbc;

    @Override
    public List<Stored> of(String userId) {
        return jdbc.sql("""
                        select merchant_id, created_at from account.favourites
                         where user_id = :u order by created_at desc, merchant_id
                        """)
                .param("u", userId)
                .query((rs, _) -> new Stored(rs.getString("merchant_id"), JdbcTimes.requiredInstant(rs, "created_at")))
                .list();
    }

    @Override
    public void add(String userId, String merchantId, Instant at) {
        jdbc.sql("""
                        insert into account.favourites (user_id, merchant_id, created_at) values (:u, :m, :at)
                        on conflict (user_id, merchant_id) do nothing
                        """)
                .param("u", userId)
                .param("m", merchantId)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    @Override
    public void remove(String userId, String merchantId) {
        jdbc.sql("delete from account.favourites where user_id = :u and merchant_id = :m")
                .param("u", userId)
                .param("m", merchantId)
                .update();
    }

    @Override
    public int count(String userId) {
        return jdbc.sql("select count(*) from account.favourites where user_id = :u")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }
}
