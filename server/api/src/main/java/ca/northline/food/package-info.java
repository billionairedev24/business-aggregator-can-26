/**
 * Food: the kitchen side of Northline — menus → sections → items with allergens, modifier groups, combos and deals,
 * opening hours / prep / capacity, and the live-orders display (KDS). Built by the kitchen workstream; layout as in
 * {@code merchants}. Food orders themselves live in {@code orders}; the kitchen keeps its own ticket per order and
 * publishes {@code order.accepted / order.ready / order.handed_off} for orders and payments.
 */
@ApplicationModule(displayName = "food")
@NullMarked
package ca.northline.food;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
