/**
 * Promotions (mobile gaps part 2): promo codes Northline staff make in the console — percent or amount off, a minimum
 * spend, a validity window, limits per customer and overall, funded by Northline (platform-wide) or by one business
 * (its own lines only) — and points spent at checkout. Checkout (orders, hire) prices a basket with
 * {@link ca.northline.promotions.api.Promotions}, reserves while the card is authorized, redeems when the order or
 * booking is placed and releases when the checkout is abandoned. Payments records who funds what in the ledger; points
 * a refund gives back return here ({@code PointsReturned}) and go to the wallet. Layout as in {@code merchants}.
 */
@ApplicationModule(displayName = "promotions")
@NullMarked
package ca.northline.promotions;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
