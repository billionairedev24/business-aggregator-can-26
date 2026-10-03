package ca.northline.food.api;

import java.util.Collection;
import java.util.Map;

/** S-120: how far each kitchen's menu is — for the console's pilot onboarding board. */
public interface MenuReadiness {

    /** Kitchens without a dish are absent. */
    Map<String, Counts> counts(Collection<String> merchantIds);

    /**
     * @param total every dish, drafts included
     * @param submitted published dishes (they go live with the approval and a photo)
     * @param live dishes customers can see: published, approved and on a live menu
     */
    record Counts(int total, int submitted, int live) {}
}
