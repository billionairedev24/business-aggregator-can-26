package ca.northline.booking.persistence;

import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

interface BookingRowRepository extends ListCrudRepository<BookingRow, String> {

    Optional<BookingRow> findByIdAndMerchantId(String id, String merchantId);
}
