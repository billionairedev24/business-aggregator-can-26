/**
 * Public API of the booking module: events ({@code quote.*}, {@code booking.*}) and the read interfaces other modules
 * use ({@link ca.northline.booking.api.BookingCalendar} for availability, {@link ca.northline.booking.api.BookingInsights}
 * for the Studio dashboard).
 */
@NamedInterface("api")
@NullMarked
package ca.northline.booking.api;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
