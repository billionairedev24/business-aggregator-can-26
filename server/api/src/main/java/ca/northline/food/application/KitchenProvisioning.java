package ca.northline.food.application;

/** Reactions to the kitchen's onboarding (see {@link KitchenOnboardingListener}). Idempotent. */
public interface KitchenProvisioning {

    /**
     * A submitted kitchen gets a draft "Dinner menu" with the editor's sections (Starters, Mains, Drinks, Dessert) so the
     * onboarding "First listings" step can add items. No-op when the kitchen already has a menu.
     */
    void starterMenu(String merchantId);

    /** Re-runs the allergen / photo / approval audit of published items, e.g. once Northline approves the kitchen. */
    void reaudit(String merchantId);
}
