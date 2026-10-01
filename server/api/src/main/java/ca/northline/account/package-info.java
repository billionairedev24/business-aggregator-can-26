/**
 * Account: the signed-in consumer's own area on the web (S-58 … S-60, design 06 {@code orders} and {@code account}) —
 * orders &amp; bookings, favourites, wallet &amp; points, the account menu's values and "Your week" on the home page. It
 * owns only what no other module keeps (favourites, schema {@code account}) and composes the rest from the other
 * modules' {@code api} packages. Every endpoint is under {@code /api/v1/me} and accepts single-factor sessions.
 */
@ApplicationModule(displayName = "account")
@NullMarked
package ca.northline.account;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
