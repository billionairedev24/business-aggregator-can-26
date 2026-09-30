/**
 * Adapters for systems outside Postgres: media storage, image inspection, spreadsheet parsing and commerce
 * integrations (Shopify, Square, Lightspeed). Local fakes run under the {@code local} and {@code test} profiles. Also
 * the ports answered by other modules' public APIs (licences from {@code merchants.api}, S-37).
 */
@NullMarked
package ca.northline.catalogue.adapters;

import org.jspecify.annotations.NullMarked;
