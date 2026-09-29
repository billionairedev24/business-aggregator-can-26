/**
 * Developer: a business's API keys (hashed), partner webhook endpoints (HMAC-signed) and the audit log of privileged
 * Studio actions — schema {@code developer} (V015, V083). Studio: Settings › API &amp; integrations, Settings › Security ›
 * Audit log. Layout as in {@code merchants} (api / web / application / domain / persistence / integration).
 */
@ApplicationModule(displayName = "developer")
@NullMarked
package ca.northline.developer;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
