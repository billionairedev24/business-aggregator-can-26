package ca.northline.payments.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface EscrowRowRepository extends ListCrudRepository<EscrowRow, String> {
    Optional<EscrowRow> findByRefTypeAndRefId(String refType, String refId);

    @Query("""
            select * from payments.escrows
             where state = 'held' and release_at <= :now
             order by release_at limit :limit""")
    List<EscrowRow> releasable(Instant now, int limit);

    @Query("""
            select e.* from payments.escrows e
              join payments.payment_intents p on p.id = e.payment_intent_id
             where e.fulfilled_at is null and e.state in ('held', 'disputed') and p.state = 'authorized'
               and coalesce(p.capture_before, p.authorized_at + interval '7 days') < :before
             order by coalesce(p.capture_before, p.authorized_at) limit :limit""")
    List<EscrowRow> authorizationsLapsingBefore(Instant before, int limit);
}
