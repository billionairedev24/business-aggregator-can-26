package ca.northline.catalogue.api;

import java.util.Collection;
import java.util.Map;

/**
 * S-120: how far each business's catalogue is — for the console's pilot onboarding board. Offers and services
 * together; drafts count only in {@code total}.
 */
public interface ListingReadiness {

    /** Businesses without a listing are absent. */
    Map<String, Counts> counts(Collection<String> merchantIds);

    /**
     * @param total every listing, drafts included
     * @param submitted listings handed to vetting: waiting or approved
     * @param live listings customers can see (approved and not hidden)
     */
    record Counts(int total, int submitted, int live) {
        public static final Counts NONE = new Counts(0, 0, 0);
    }
}
