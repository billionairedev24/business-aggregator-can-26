package ca.northline.payments.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

interface PayoutAccountRowRepository extends ListCrudRepository<PayoutAccountRow, String> {
    Optional<PayoutAccountRow> findByIdAndMerchantId(String id, String merchantId);

    Optional<PayoutAccountRow> findFirstByMerchantIdAndState(String merchantId, String state);

    List<PayoutAccountRow> findByStateAndEffectiveAtLessThanEqual(String state, Instant now);

    List<PayoutAccountRow> findByFinancialConnectionsAccount(String financialConnectionsAccount);
}
