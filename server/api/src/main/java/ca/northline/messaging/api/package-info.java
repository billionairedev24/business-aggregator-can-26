/**
 * Public API of the messaging module: its events, {@link ca.northline.messaging.api.Conversations} (booking, orders
 * and trust open customer threads through it) and the {@link ca.northline.messaging.api.CaseReferences} SPI other
 * modules implement to offer their records in the help form's "Related to" list.
 */
@NamedInterface("api")
@NullMarked
package ca.northline.messaging.api;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
