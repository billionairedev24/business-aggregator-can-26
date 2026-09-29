/**
 * Adapters implementing the catalogue outbound ports over Postgres. The tables carry {@code text[]} and {@code jsonb}
 * columns and the reads are joins, so they use {@code JdbcClient} rather than Spring Data derived queries.
 */
@NullMarked
package ca.northline.catalogue.persistence;

import org.jspecify.annotations.NullMarked;
