package ca.northline.payments.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface PayoutRowRepository extends ListCrudRepository<PayoutRow, String> {
    @Query("select * from payments.payouts where merchant_id = :merchantId order by created_at desc limit :limit")
    List<PayoutRow> history(String merchantId, int limit);

    @Query("select * from payments.payouts where state = 'in_transit' order by arrives_at limit :limit")
    List<PayoutRow> inTransit(int limit);

    Optional<PayoutRow> findFirstByMerchantIdOrderByCreatedAtDesc(String merchantId);

    Optional<PayoutRow> findByStripePayout(String stripePayout);
}
