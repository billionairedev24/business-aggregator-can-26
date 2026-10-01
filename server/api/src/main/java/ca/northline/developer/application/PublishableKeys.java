package ca.northline.developer.application;

import ca.northline.developer.application.DeveloperUseCases.Actor;
import ca.northline.developer.domain.PublishableKey;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** S-76: Settings › API › "Embed your store" — the business's publishable key. */
public interface PublishableKeys {

    Optional<PublishableKey> current(String merchantId);

    /**
     * Issues the business's first key, or replaces the current one (the old key stops working at once: embeds using it
     * show nothing until the site is updated). Keeps the allowed sites unless {@code allowedOrigins} is given.
     */
    PublishableKey roll(Actor actor, @Nullable List<String> allowedOrigins);

    /** Sets the sites the current key's embed answers on (empty = any site). 404 without a key. */
    PublishableKey allowOrigins(Actor actor, List<String> allowedOrigins);
}
