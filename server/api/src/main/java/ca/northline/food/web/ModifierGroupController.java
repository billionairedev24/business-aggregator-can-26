package ca.northline.food.web;

import static ca.northline.shared.security.MerchantPermission.DELETE;
import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.food.application.ModifierUseCases.EditModifierGroups;
import ca.northline.food.application.ModifierUseCases.GroupView;
import ca.northline.food.application.ModifierUseCases.ListModifierGroups;
import ca.northline.food.web.KitchenRequests.ModifierGroupRequest;
import ca.northline.food.web.KitchenRequests.OptionRequest;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Modifier groups. {@code GET} is also the onboarding contract ({@code {items:[{id, name, …}]}}). */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/modifier-groups")
@RequiredArgsConstructor
class ModifierGroupController {

    private final ListModifierGroups list;
    private final EditModifierGroups edit;

    @GetMapping
    @RequiresMerchant(VIEW)
    ListResponse<GroupView> groups(@PathVariable String merchantId) {
        return new ListResponse<>(list.groups(merchantId));
    }

    @PostMapping
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    GroupView create(@PathVariable String merchantId, @Valid @RequestBody ModifierGroupRequest body) {
        return edit.create(merchantId, body.toCommand());
    }

    @PutMapping("/{groupId}")
    @RequiresMerchant(EDIT)
    GroupView update(
            @PathVariable String merchantId,
            @PathVariable String groupId,
            @Valid @RequestBody ModifierGroupRequest body) {
        return edit.update(merchantId, groupId, body.toCommand());
    }

    /** "+ option". */
    @PostMapping("/{groupId}/options")
    @RequiresMerchant(EDIT)
    GroupView addOption(
            @PathVariable String merchantId, @PathVariable String groupId, @Valid @RequestBody OptionRequest body) {
        return edit.addOption(merchantId, groupId, body.toCommand());
    }

    @DeleteMapping("/{groupId}")
    @RequiresMerchant(DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String merchantId, @PathVariable String groupId) {
        edit.delete(merchantId, groupId);
    }
}
