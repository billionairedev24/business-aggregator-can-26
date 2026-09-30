package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.Conflict;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The {@link CommerceCatalogSource} per platform. */
@Component
class CommerceSources {

    static final String UNAVAILABLE = "This platform can't be connected yet.";

    private final Map<CommerceProvider, CommerceCatalogSource> byProvider = new EnumMap<>(CommerceProvider.class);

    CommerceSources(List<CommerceCatalogSource> sources) {
        sources.forEach(s -> byProvider.put(s.provider(), s));
    }

    CommerceCatalogSource get(CommerceProvider provider) {
        var source = byProvider.get(provider);
        if (source == null) {
            throw new Conflict("commerce_provider_unavailable", UNAVAILABLE);
        }
        return source;
    }

    boolean available(CommerceProvider provider) {
        var source = byProvider.get(provider);
        return source != null && source.available();
    }
}
