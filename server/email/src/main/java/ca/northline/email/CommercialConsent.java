package ca.northline.email;

import ca.northline.email.MessageClasses.ConsentCategory;

/**
 * Whether a person has consented, now, to commercial messages of one category (S-108) — the app's consent records
 * (the api's messaging module; the worker reads the same table). Asked by the {@link Mailer} right before a commercial
 * email goes, and by the worker before a commercial SMS or push. Without an implementation nothing commercial is sent.
 */
@FunctionalInterface
public interface CommercialConsent {

    /** Refuses everything: the default when an app declares no consent source. */
    CommercialConsent NONE = (_, _) -> false;

    boolean allows(String userId, ConsentCategory category);
}
