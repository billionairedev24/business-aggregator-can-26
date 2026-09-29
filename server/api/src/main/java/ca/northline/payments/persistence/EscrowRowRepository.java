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
}
