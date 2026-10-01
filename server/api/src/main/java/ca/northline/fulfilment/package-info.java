/**
 * Fulfilment: deliveries, courier runs and their stops ({@code fulfilment} schema). S-64 gave it the read side other
 * modules need (the courier's pickup at a kitchen); S-86 plans pooled and direct runs, assigns couriers on shift and
 * runs the courier's stops with proof of delivery. It depends on no module that depends on it: orders hands deliveries
 * over through {@code fulfilment.api.DeliveryRequests} and listens to its events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "fulfilment")
@NullMarked
package ca.northline.fulfilment;

import org.jspecify.annotations.NullMarked;
