package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.merchants.application.RenameMerchant;
import ca.northline.merchants.application.ViewMerchant;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Studio header: {@code GET/PATCH /api/v1/merchants/{merchantId}}. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class MerchantController {

    private final ViewMerchant viewMerchant;
    private final RenameMerchant renameMerchant;
    private final MerchantWebMapper mapper;

    @GetMapping
    @RequiresMerchant(VIEW)
    MerchantResponse get(@PathVariable String merchantId) {
        return mapper.toResponse(viewMerchant.view(merchantId));
    }

    @PatchMapping
    @RequiresMerchant(MANAGE)
    MerchantResponse update(
            @PathVariable String merchantId, @Valid @RequestBody UpdateMerchantRequest body, CurrentMember member) {
        var command = new RenameMerchant.Command(merchantId, body.displayName(), member.userId());
        return mapper.toResponse(renameMerchant.rename(command));
    }
}
