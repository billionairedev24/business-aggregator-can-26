/**
 * Fulfilment: courier runs and their stops ({@code fulfilment} schema). Its first code (S-64) is the read side other
 * modules need — the courier's pickup at a kitchen — so they stop reading these tables directly.
 */
@org.springframework.modulith.ApplicationModule(displayName = "fulfilment")
@NullMarked
package ca.northline.fulfilment;

import org.jspecify.annotations.NullMarked;
