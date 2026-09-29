package ca.northline.merchants.persistence;

import ca.northline.merchants.domain.DisplayName;
import ca.northline.merchants.domain.Merchant;
import ca.northline.shared.CodedEnums;
import org.mapstruct.Mapper;

/** Row ↔ aggregate. MapStruct generates the implementation (a Spring bean, see {@code mapstruct.defaultComponentModel}). */
@Mapper(uses = CodedEnums.class)
interface MerchantRowMapper {

    Merchant toDomain(MerchantRow row);

    MerchantRow toRow(Merchant merchant);

    default DisplayName displayName(String value) {
        return new DisplayName(value);
    }

    default String displayName(DisplayName name) {
        return name.value();
    }
}
