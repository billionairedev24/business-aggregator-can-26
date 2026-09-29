package ca.northline.booking.persistence;

import ca.northline.booking.domain.Booking;
import ca.northline.shared.CodedEnums;
import org.mapstruct.Mapper;

@Mapper(uses = CodedEnums.class)
interface BookingRowMapper {

    Booking toDomain(BookingRow row);

    BookingRow toRow(Booking booking);

    default long version(@org.jspecify.annotations.Nullable Long version) {
        return version == null ? 0 : version;
    }
}
