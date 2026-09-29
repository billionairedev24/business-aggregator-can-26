package ca.northline.merchants.web;

import ca.northline.merchants.application.BusinessSummary;
import ca.northline.merchants.domain.DisplayName;
import ca.northline.merchants.domain.Merchant;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface MerchantWebMapper {

    @Mapping(target = "role", ignore = true)
    @Mapping(target = "teamCount", ignore = true)
    MerchantResponse toResponse(Merchant merchant);

    @Mapping(target = "id", source = "merchantId")
    BusinessResponse toResponse(BusinessSummary business);

    List<BusinessResponse> toResponses(List<BusinessSummary> businesses);

    default String displayName(DisplayName name) {
        return name.value();
    }
}
