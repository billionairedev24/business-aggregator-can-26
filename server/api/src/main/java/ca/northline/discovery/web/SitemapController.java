package ca.northline.discovery.web;

import ca.northline.discovery.application.ListSitemap;
import ca.northline.shared.PublicPages;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The consumer site's sitemap data (S-63), public and cacheable for an hour (crawlers read it, people don't):
 *
 * <pre>
 * GET /api/v1/public/sitemap                         {pageSize, sections: [{name, count, pages}]}
 * GET /api/v1/public/sitemap/{section}?page=1        {items: [{key, customDomain, updatedAt}]} — 404 for an unknown section
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/public/sitemap")
@RequiredArgsConstructor
class SitemapController {

    static final String PAGE_RANGE = "Choose a page between 1 and " + ListSitemap.MAX_PAGE + ".";
    private static final CacheControl HOUR =
            CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final ListSitemap sitemap;

    record Index(int pageSize, List<ListSitemap.Section> sections) {}

    record Items(List<PublicPages.Page> items) {}

    @GetMapping
    ResponseEntity<Index> index() {
        return ResponseEntity.ok().cacheControl(HOUR).body(new Index(ListSitemap.PAGE_SIZE, sitemap.sections()));
    }

    @GetMapping("/{section}")
    ResponseEntity<Items> section(@PathVariable String section, @RequestParam(defaultValue = "1") int page) {
        if (page < 1 || page > ListSitemap.MAX_PAGE) {
            throw RuleViolation.of("page", "range", PAGE_RANGE);
        }
        return ResponseEntity.ok().cacheControl(HOUR).body(new Items(sitemap.page(section, page)));
    }
}
