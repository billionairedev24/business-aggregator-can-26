package ca.northline.food.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.food.application.PosImportUseCases.ImportFromPos;
import ca.northline.food.application.PosImportUseCases.ManagePosConnections;
import ca.northline.food.application.PosImportViews.Applied;
import ca.northline.food.application.PosImportViews.ConnectStart;
import ca.northline.food.application.PosImportViews.ConnectionView;
import ca.northline.food.application.PosImportViews.Preview;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.food.domain.PosProvider;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kitchen › Menu › "Import from POS" (S-36): POS connections (owner connects), preview of an import with its diff,
 * apply / discard (owner and cooks).
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class PosImportController {

    private final ManagePosConnections connections;
    private final ImportFromPos imports;

    /** {@code menuId}: where the Studio returns after the POS's consent page; {@code restaurantId}: Toast's GUID. */
    record ConnectRequest(@Nullable String menuId, @Nullable String restaurantId) {}

    record PreviewRequest(@Nullable String provider) {}

    @GetMapping("/pos/connections")
    @RequiresMerchant(VIEW)
    ListResponse<ConnectionView> connections(@PathVariable String merchantId) {
        return new ListResponse<>(connections.connections(merchantId));
    }

    @PostMapping("/pos/{provider}/connect")
    @RequiresMerchant(MANAGE)
    ConnectStart connect(
            @PathVariable String merchantId,
            @PathVariable String provider,
            @RequestBody(required = false) @Nullable ConnectRequest body,
            CurrentMember member) {
        return connections.connect(
                merchantId,
                member.userId(),
                provider(provider),
                body == null ? null : body.menuId(),
                body == null ? null : body.restaurantId());
    }

    @PostMapping("/pos/{provider}/disconnect")
    @RequiresMerchant(MANAGE)
    ConnectionView disconnect(@PathVariable String merchantId, @PathVariable String provider) {
        return connections.disconnect(merchantId, provider(provider));
    }

    @PostMapping("/menus/{menuId}/pos-imports")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(EDIT)
    Preview preview(
            @PathVariable String merchantId,
            @PathVariable String menuId,
            @RequestBody(required = false) @Nullable PreviewRequest body,
            CurrentMember member) {
        var code = body == null ? null : body.provider();
        PosProvider provider;
        try {
            provider = CodedEnum.fromCode(PosProvider.class, code == null ? "" : code);
        } catch (IllegalArgumentException _) {
            throw RuleViolation.of("provider", "required", KitchenMessages.POS_REQUIRED);
        }
        return imports.preview(merchantId, menuId, provider, member.userId());
    }

    @GetMapping("/pos-imports/{importId}")
    @RequiresMerchant(VIEW)
    Preview view(@PathVariable String merchantId, @PathVariable String importId) {
        return imports.view(merchantId, importId);
    }

    @PostMapping("/pos-imports/{importId}/apply")
    @RequiresMerchant(EDIT)
    Applied apply(@PathVariable String merchantId, @PathVariable String importId) {
        return imports.apply(merchantId, importId);
    }

    @PostMapping("/pos-imports/{importId}/discard")
    @RequiresMerchant(EDIT)
    Preview discard(@PathVariable String merchantId, @PathVariable String importId) {
        return imports.discard(merchantId, importId);
    }

    private static PosProvider provider(String code) {
        try {
            return CodedEnum.fromCode(PosProvider.class, code);
        } catch (IllegalArgumentException _) {
            throw new NotFound("pos", code);
        }
    }
}
