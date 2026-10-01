package ca.northline.discovery.application;

import ca.northline.shared.NotFound;
import ca.northline.shared.PublicPages;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ListSitemap} over every module's {@link PublicPages} section. */
@Service
@Transactional(readOnly = true)
class SitemapService implements ListSitemap {

    private final Map<String, PublicPages> sections;

    SitemapService(List<PublicPages> sections) {
        this.sections = sections.stream()
                .sorted(Comparator.comparing(PublicPages::section))
                .collect(Collectors.toMap(
                        PublicPages::section,
                        Function.identity(),
                        (a, _) -> {
                            throw new IllegalStateException("Two sitemap sections named " + a.section());
                        },
                        LinkedHashMap::new));
    }

    @Override
    public List<Section> sections() {
        return sections.values().stream()
                .map(s -> {
                    var count = s.count();
                    return new Section(s.section(), count, (int) ((count + PAGE_SIZE - 1) / PAGE_SIZE));
                })
                .toList();
    }

    @Override
    public List<PublicPages.Page> page(String section, int page) {
        var source = sections.get(section);
        if (source == null) {
            throw new NotFound("sitemap section", section);
        }
        return source.list((long) (page - 1) * PAGE_SIZE, PAGE_SIZE);
    }
}
