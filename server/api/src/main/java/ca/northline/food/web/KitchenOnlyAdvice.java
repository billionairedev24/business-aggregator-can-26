package ca.northline.food.web;

import ca.northline.food.application.KitchenUseCases.RequireKitchen;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * S-73: every Studio kitchen endpoint ({@code …/kitchen/*}, menus, menu items, modifier groups, combos, POS import)
 * answers 404 for a business that is not a kitchen. Runs before each handler of these controllers — after the
 * {@code @RequiresMerchant} interceptor, so a non-member still gets 403, and before the request body is validated, so
 * a non-kitchen never sees a kitchen's 422s.
 */
@ControllerAdvice(
        assignableTypes = {
            KitchenController.class,
            MenuController.class,
            MenuItemController.class,
            ModifierGroupController.class,
            ComboController.class,
            PosImportController.class
        })
@RequiredArgsConstructor
class KitchenOnlyAdvice {

    private final RequireKitchen kitchens;

    @ModelAttribute
    void requireKitchen(@PathVariable String merchantId) {
        kitchens.require(merchantId);
    }
}
