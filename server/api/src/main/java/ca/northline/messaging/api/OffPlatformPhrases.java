package ca.northline.messaging.api;

import java.util.List;

/**
 * S-93: the phrases staff added to the off-platform payment detector (trust &amp; safety rules, console {@code trust}),
 * on top of its built-in patterns. Declared here and implemented by the trust module, which owns the rules (trust
 * already depends on messaging, so messaging can't ask trust).
 */
public interface OffPlatformPhrases {

    /** Lower-case phrases; empty when none are configured. */
    List<String> phrases();
}
