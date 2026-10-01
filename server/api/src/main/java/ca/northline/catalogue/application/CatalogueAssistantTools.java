package ca.northline.catalogue.application;

import ca.northline.ai.api.AssistantTool;
import ca.northline.catalogue.domain.ListingKind;
import ca.northline.shared.CodedEnums;
import ca.northline.shared.security.MerchantPermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** S-130: the Studio assistant's listings tool, over the Listings screen's {@link BrowseListings}. */
final class CatalogueAssistantTools {
    private CatalogueAssistantTools() {}

    @Component
    @RequiredArgsConstructor
    static final class ListListingsTool implements AssistantTool {
        private final BrowseListings listings;

        @Override
        public String name() {
            return "list_listings";
        }

        @Override
        public String description() {
            return "The business's listings (services and products), newest first: name, kind, price (CAD cents),"
                    + " stock, sales in the last 30 days, vetting state (draft, pending, approved, rejected) and its"
                    + " flags, live or hidden, category, duration and instant booking for services.";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("kind", Map.of("type", "string", "enum", List.of("service", "product")));
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.VIEW;
        }

        @Override
        public String screen() {
            return "products";
        }

        @Override
        public Result run(Call call) {
            var kindCode = call.text("kind");
            var kind = kindCode == null ? null : CodedEnums.fromCode(ListingKind.class, kindCode);
            var rows = listings.list(call.merchantId(), kind, 60, call.locale()).stream()
                    .map(l -> {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("id", l.id());
                        m.put("name", l.name());
                        m.put("kind", l.kind());
                        m.put("priceCents", l.priceCents());
                        m.put("stock", l.stock());
                        m.put("sales30d", l.sales30d());
                        m.put("vetting", l.vetting());
                        m.put("flags", l.flags());
                        m.put("status", l.status());
                        m.put("category", l.categoryName());
                        m.put("durationMin", l.durationMin());
                        m.put("instantBook", l.instantBook());
                        return m;
                    })
                    .toList();
            return new Result(Map.of("listings", rows), "listings → " + rows.size());
        }
    }
}
