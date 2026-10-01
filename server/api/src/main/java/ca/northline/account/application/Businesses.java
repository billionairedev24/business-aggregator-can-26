package ca.northline.account.application;

import ca.northline.merchants.api.BusinessNames;
import ca.northline.merchants.api.PublicDirectory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names, tiers and pages of the businesses a person dealt with: the public directory for active businesses, the
 * business name alone for one that stopped trading (its old orders still show who they were with).
 */
@Component
@RequiredArgsConstructor
class Businesses {

    record Business(
            String merchantId,
            String name,
            String type,
            String tier,
            @Nullable String slug,
            @Nullable String categoryId,
            boolean active) {}

    private final PublicDirectory directory;
    private final BusinessNames names;

    Map<String, Business> of(Collection<String> merchantIds) {
        var out = new LinkedHashMap<String, Business>();
        for (var id : merchantIds) {
            if (!out.containsKey(id)) {
                one(id).ifPresent(b -> out.put(id, b));
            }
        }
        return out;
    }

    Optional<Business> one(String merchantId) {
        return directory
                .byId(merchantId)
                .map(b -> new Business(
                        b.merchantId(),
                        b.displayName(),
                        b.type(),
                        b.tier(),
                        b.slug(),
                        b.categoryIds().isEmpty() ? null : b.categoryIds().getFirst(),
                        true))
                .or(() -> names.displayName(merchantId)
                        .map(name -> new Business(merchantId, name, "provider", "registered", null, null, false)));
    }
}
