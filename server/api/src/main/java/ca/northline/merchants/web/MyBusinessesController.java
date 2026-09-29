package ca.northline.merchants.web;

import ca.northline.merchants.application.ListMyBusinesses;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Studio "Switch business" menu: {@code GET /api/v1/me/businesses}. Any signed-in user; no MFA needed to list. */
@RestController
@RequiredArgsConstructor
class MyBusinessesController {

    private final ListMyBusinesses listMyBusinesses;
    private final MerchantWebMapper mapper;

    @GetMapping("/api/v1/me/businesses")
    ListResponse<BusinessResponse> list(CurrentUser user) {
        return new ListResponse<>(mapper.toResponses(listMyBusinesses.of(user.userId())));
    }
}
