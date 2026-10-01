/**
 * Search (S-44): the public search and suggestion API over the Elasticsearch read model ({@code listings_en} /
 * {@code listings_fr}, written only by the worker — S-42/S-43). The api never writes to the indices. See
 * {@code docs/runbooks/search.md}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "search")
@NullMarked
package ca.northline.search;

import org.jspecify.annotations.NullMarked;
