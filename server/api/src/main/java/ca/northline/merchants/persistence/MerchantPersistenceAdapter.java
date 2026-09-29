package ca.northline.merchants.persistence;

import ca.northline.merchants.application.MerchantRepository;
import ca.northline.merchants.domain.Merchant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MerchantPersistenceAdapter implements MerchantRepository {

    private final MerchantRowRepository rows;
    private final MerchantRowMapper mapper;

    @Override
    public Optional<Merchant> findById(String id) {
        return rows.findById(id).map(mapper::toDomain);
    }

    @Override
    public Merchant save(Merchant merchant) {
        return mapper.toDomain(rows.save(mapper.toRow(merchant)));
    }
}
