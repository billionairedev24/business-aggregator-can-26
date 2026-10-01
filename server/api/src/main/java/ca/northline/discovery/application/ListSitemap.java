package ca.northline.discovery.application;

import ca.northline.shared.PublicPages;
import java.util.List;

/**
 * Use case: the consumer site's sitemap (S-63) — which sections exist and how many pages of {@link #PAGE_SIZE} each has,
 * then one page of a section. The web app renders the XML (it knows its routes and origins).
 */
public interface ListSitemap {

    /** Pages per sitemap file: well under the protocol's 50 000 URLs and 50 MB. */
    int PAGE_SIZE = 5_000;

    /** Highest page number accepted (a quarter of a billion pages per section). */
    int MAX_PAGE = 50_000;

    List<Section> sections();

    /** Page {@code page} (from 1) of {@code section}; empty past the end. */
    List<PublicPages.Page> page(String section, int page);

    record Section(String name, long count, int pages) {}
}
