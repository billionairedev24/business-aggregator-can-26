/**
 * Adapters implementing the messaging outbound ports over Postgres. Reads are joins over {@code text[]} and
 * {@code jsonb} columns (and full-text search), so they use {@code JdbcClient}.
 */
@NullMarked
package ca.northline.messaging.persistence;

import org.jspecify.annotations.NullMarked;
