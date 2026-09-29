package ca.northline.food.web;

import static ca.northline.shared.security.MerchantPermission.DELETE;
import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.food.application.ComboUseCases.ComboView;
import ca.northline.food.application.ComboUseCases.EditCombos;
import ca.northline.food.application.ComboUseCases.ListCombos;
import ca.northline.food.web.KitchenRequests.ComboRequest;
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

/** Combos &amp; deals. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/combos")
@RequiredArgsConstructor
class ComboController {

    private final ListCombos list;
    private final EditCombos edit;

    @GetMapping
    @RequiresMerchant(VIEW)
    ListResponse<ComboView> combos(@PathVariable String merchantId) {
        return new ListResponse<>(list.combos(merchantId));
    }

    @PostMapping
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    ComboView create(@PathVariable String merchantId, @Valid @RequestBody ComboRequest body) {
        return edit.create(merchantId, body.toCommand());
    }

    @PutMapping("/{comboId}")
    @RequiresMerchant(EDIT)
    ComboView update(
            @PathVariable String merchantId, @PathVariable String comboId, @Valid @RequestBody ComboRequest body) {
        return edit.update(merchantId, comboId, body.toCommand());
    }

    @DeleteMapping("/{comboId}")
    @RequiresMerchant(DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String merchantId, @PathVariable String comboId) {
        edit.delete(merchantId, comboId);
    }
}
