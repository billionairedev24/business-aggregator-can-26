package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.SubmittedListings;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code submitted_at} of services ∪ offers, keyset-paged by (time, id). */
@Repository
@RequiredArgsConstructor
class SubmittedListingsAdapter implements SubmittedListings {

    private final JdbcClient jdbc;

    @Override
    public List<Submitted> after(Instant at, String id, int limit) {
        return jdbc.sql("""
                        select id, submitted_at from (
                          select id, submitted_at from catalogue.services where submitted_at is not null
                          union all
                          select id, submitted_at from catalogue.offers where submitted_at is not null
                        ) l
                         where (submitted_at, id) > (:at, :id)
                         order by submitted_at, id
                         limit :limit
                        """)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("id", id)
                .param("limit", limit)
                .query((rs, n) -> new Submitted(
                        rs.getString("id"),
                        rs.getObject("submitted_at", java.time.OffsetDateTime.class)
                                .toInstant()))
                .list();
    }
}
