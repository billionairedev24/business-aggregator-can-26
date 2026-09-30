/**
 * The Elasticsearch listings indices — the search read model (S-42): the versioned layout of {@code deploy/search},
 * the languages, the synonym sets, and the bootstrap that makes a cluster match them. Shared by the worker (bootstrap
 * Job, indexer, reindex) and the api (search API). See {@code docs/runbooks/search.md}.
 */
@NullMarked
package ca.northline.searchindex;

import org.jspecify.annotations.NullMarked;
