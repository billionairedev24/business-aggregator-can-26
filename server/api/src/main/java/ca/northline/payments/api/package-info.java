/**
 * Public API of the payments module: the events it publishes and the small ports other modules call (booking, orders
 * and food hold and release escrow; the consumer app opens refunds and disputes; the console decides them; the Studio
 * dashboard reads earnings).
 */
@NamedInterface("api")
@NullMarked
package ca.northline.payments.api;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
