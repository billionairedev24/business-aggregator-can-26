/**
 * Catalogue: service listings, product listings (offers on shared GTIN-matched catalogue records or seller-owned
 * records, Amazon-ASIN style), variants, media, automated vetting, bulk imports and commerce integrations. Layout as in
 * {@code merchants} (docs/BACKEND_CONVENTIONS.md); systems outside Postgres sit behind ports with adapters in
 * {@code catalogue.adapters}.
 */
@ApplicationModule(displayName = "catalogue")
@NullMarked
package ca.northline.catalogue;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
