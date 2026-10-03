package ca.northline.food.api;

import java.util.Collection;
import java.util.Map;

/** S-120: how far each kitchen's menu is — for the console's pilot onboarding board. */
public interface MenuReadiness {

    /** Kitchens with neither a dish nor their kitchen set up are absent. */
    Map<String, Counts> counts(Collection<String> merchantIds);

    /**
     * @param total every dish, drafts included
     * @param submitted published dishes (they go live with the approval and a photo)
     * @param live dishes customers can see: published, approved and on a live menu
     * @param setUp the kitchen saved its fulfilment and prep settings: until then the market's kitchen list leaves it
     *     out (the public kitchens query reads {@code food.kitchen_settings})
     */
    record Counts(int total, int submitted, int live, boolean setUp) {}
}
