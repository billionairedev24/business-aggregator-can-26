package ca.northline.merchants.persistence;

import org.springframework.data.repository.ListCrudRepository;

interface MerchantRowRepository extends ListCrudRepository<MerchantRow, String> {}
