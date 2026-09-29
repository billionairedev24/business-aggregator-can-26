package ca.northline.payments.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface RefundRowRepository extends ListCrudRepository<RefundRow, String> {
    Optional<RefundRow> findByIdAndMerchantId(String id, String merchantId);

    @Query("""
            select * from payments.refunds
             where state = 'seller_review' and contest_by <= :now
             order by contest_by limit :limit""")
    List<RefundRow> lapsed(Instant now, int limit);

    @Query(
            "select * from payments.refunds where state = 'approved' order by decided_at nulls first, created_at limit :limit")
    List<RefundRow> approved(int limit);

    @Query("select * from payments.refunds where merchant_id = :merchantId order by created_at desc limit :limit")
    List<RefundRow> latest(String merchantId, int limit);

    int countByMerchantIdAndState(String merchantId, String state);
}
