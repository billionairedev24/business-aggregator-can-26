/**
 * Plumbing shared by the modules that connect merchants' outside accounts over OAuth (S-35 catalogue sync, S-36 POS menu
 * import): {@link ca.northline.shared.integration.ProviderHttp} ({@code @HttpExchange} clients over the JDK client,
 * query/form encoding, HMAC), {@link ca.northline.shared.integration.Backoff} (429 / 5xx retries) and the {@link
 * ca.northline.shared.integration.OAuthCallback} SPI behind the one public redirect URI per platform
 * ({@code /api/v1/commerce/oauth/<platform>/callback}): a platform like Square takes a single redirect URL per app, so
 * the module that started the consent claims the callback by its state.
 */
@NamedInterface("integration")
@NullMarked
package ca.northline.shared.integration;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
