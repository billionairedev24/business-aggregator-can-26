package ca.northline.worker.search;

import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.ListingDocument.Completion;
import ca.northline.searchindex.ListingDocument.GeoPoint;
import ca.northline.searchindex.SearchLanguage;
import ca.northline.worker.search.DocumentSource.DishRow;
import ca.northline.worker.search.DocumentSource.MerchantFacts;
import ca.northline.worker.search.DocumentSource.OfferRow;
import ca.northline.worker.search.DocumentSource.ServiceRow;
import ca.northline.worker.search.DocumentSource.Snapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a {@link Snapshot} into the documents of both languages, and says which of the scope's ids are not (or no
 * longer) visible — the projection deletes those. What customers see, and so what is indexed:
 *
 * <ul>
 *   <li>only merchants whose status is {@code active} and that have a market (province);
 *   <li>services and product offers {@code vetting = approved} and {@code status = live};
 *   <li>dishes {@code published} and {@code approved} on a {@code live} menu (sold out today stays indexed with
 *       {@code soldOutOn}: it comes back tomorrow without any event);
 *   <li>the merchant itself once its page (storefront) is published.
 * </ul>
 */
final class DocumentBuilder {

    private static final int MAX_SUGGEST_WORDS = 5;

    private final CategoryTree categories;
    private final JsonMapper json;

    DocumentBuilder(CategoryTree categories, JsonMapper json) {
        this.categories = categories;
        this.json = json;
    }

    /** The visible documents per language and the ids of the scope to remove. */
    record Built(Map<SearchLanguage, List<ListingDocument>> documents, List<String> hidden) {

        List<ListingDocument> of(SearchLanguage language) {
            return documents.getOrDefault(language, List.of());
        }

        List<String> visibleIds() {
            return of(SearchLanguage.EN).stream().map(ListingDocument::id).toList();
        }
    }

    /** @param includeMerchant the merchant's own document is part of the scope (whole-merchant refreshes) */
    Built build(Snapshot snapshot, boolean includeMerchant) {
        var merchant = snapshot.merchant().filter(MerchantFacts::active).orElse(null);
        var hidden = new ArrayList<String>();
        Map<SearchLanguage, List<ListingDocument>> docs =
                Map.of(SearchLanguage.EN, new ArrayList<>(), SearchLanguage.FR, new ArrayList<>());
        for (var s : snapshot.services()) {
            add(
                    merchant,
                    s.visible(),
                    s.id(),
                    hidden,
                    docs,
                    lang -> service(Objects.requireNonNull(merchant), s, lang));
        }
        for (var o : snapshot.offers()) {
            add(merchant, o.visible(), o.id(), hidden, docs, lang -> offer(Objects.requireNonNull(merchant), o, lang));
        }
        for (var d : snapshot.dishes()) {
            add(merchant, d.visible(), d.id(), hidden, docs, lang -> dish(Objects.requireNonNull(merchant), d, lang));
        }
        if (includeMerchant) {
            snapshot.merchant()
                    .ifPresent(m -> add(
                            merchant,
                            m.pagePublished(),
                            m.id(),
                            hidden,
                            docs,
                            lang -> merchantDocument(m, Objects.requireNonNull(docs.get(lang)), lang)));
        }
        return new Built(
                Map.of(
                        SearchLanguage.EN, List.copyOf(Objects.requireNonNull(docs.get(SearchLanguage.EN))),
                        SearchLanguage.FR, List.copyOf(Objects.requireNonNull(docs.get(SearchLanguage.FR)))),
                List.copyOf(hidden));
    }

    private static void add(
            @Nullable MerchantFacts activeMerchant,
            boolean visible,
            String id,
            List<String> hidden,
            Map<SearchLanguage, List<ListingDocument>> docs,
            java.util.function.Function<SearchLanguage, ListingDocument> document) {
        if (activeMerchant == null || !visible) {
            hidden.add(id);
            return;
        }
        for (var language : SearchLanguage.values()) {
            Objects.requireNonNull(docs.get(language)).add(document.apply(language));
        }
    }

    private ListingDocument service(MerchantFacts m, ServiceRow s, SearchLanguage lang) {
        return finish(base(m, lang, s.categoryId() == null ? List.of() : List.of(s.categoryId()))
                .id(s.id())
                .kind(ListingDocument.SERVICE)
                .name(lang == SearchLanguage.FR ? s.nameFr() : s.nameEn())
                .description(lang == SearchLanguage.FR ? s.descriptionFr() : s.descriptionEn())
                .priceCents(s.priceCents())
                .pricingMode(s.pricingMode())
                .instantBook(s.instantBook())
                .openHours(OpenHours.of(m.serviceHours(), json))
                .sales30d(s.sales30d())
                .updatedAt(s.updatedAt()));
    }

    private ListingDocument offer(MerchantFacts m, OfferRow o, SearchLanguage lang) {
        var pooled = o.fulfilment().contains("pooled");
        return finish(base(m, lang, o.categoryId() == null ? List.of() : List.of(o.categoryId()))
                .id(o.id())
                .kind(ListingDocument.PRODUCT)
                .name(lang == SearchLanguage.FR ? o.nameFr() : o.nameEn())
                .description(o.description())
                .keywords(o.keywords())
                .priceCents(o.priceCents())
                .pricingMode("fixed")
                .fulfilment(o.fulfilment())
                .deliveryCutoffMinute(
                        pooled && m.sameDayCutoff() != null ? OpenHours.minuteOfDay(m.sameDayCutoff()) : null)
                .inStock(o.stock() > 0)
                .imageKey(o.imageId() == null ? null : "media:" + o.imageId())
                .sales30d(o.sales30d())
                .updatedAt(o.updatedAt()));
    }

    private ListingDocument dish(MerchantFacts m, DishRow d, SearchLanguage lang) {
        var keywords = Stream.concat(
                        Stream.ofNullable(d.section()), d.dietary().stream().map(t -> t.replace('_', ' ')))
                .toList();
        return finish(base(m, lang, m.categoryIds())
                .id(d.id())
                .kind(ListingDocument.FOOD)
                .name(lang == SearchLanguage.FR ? d.nameFr() : d.nameEn())
                .description(lang == SearchLanguage.FR ? d.descriptionFr() : d.descriptionEn())
                .keywords(keywords.isEmpty() ? null : String.join(" ", keywords))
                .priceCents(d.priceCents())
                .pricingMode("fixed")
                .fulfilment(m.kitchenFulfilment())
                .soldOutOn(d.soldOutOn())
                .openHours(OpenHours.of(m.kitchenHours(), json))
                .prepMinutes(m.prepMinutes() == null ? null : m.prepMinutes() + d.prepAddMinutes())
                .allergens(d.allergens())
                .dietary(d.dietary())
                .imageKey(d.photoKey() == null ? null : "object:" + d.photoKey())
                .updatedAt(d.updatedAt()));
    }

    /** The merchant's own document: its page, cheapest listing, every fulfilment it offers. */
    private ListingDocument merchantDocument(MerchantFacts m, List<ListingDocument> listings, SearchLanguage lang) {
        var kitchen = "kitchen".equals(m.type());
        var tagline = lang == SearchLanguage.FR ? m.taglineFr() : m.taglineEn();
        return finish(base(m, lang, m.categoryIds())
                .id(m.id())
                .kind(ListingDocument.MERCHANT)
                .name(m.name())
                .description(tagline != null ? tagline : m.about())
                .priceCents(listings.stream()
                        .map(ListingDocument::priceCents)
                        .filter(Objects::nonNull)
                        .min(Long::compare)
                        .orElse(null))
                .instantBook(listings.stream().anyMatch(ListingDocument::instantBook))
                .fulfilment(listings.stream()
                        .flatMap(d -> d.fulfilment().stream())
                        .distinct()
                        .toList())
                .openHours(OpenHours.of(kitchen ? m.kitchenHours() : m.serviceHours(), json))
                .prepMinutes(kitchen ? m.prepMinutes() : null)
                .sales30d(listings.stream().mapToInt(ListingDocument::sales30d).sum())
                .updatedAt(m.updatedAt()));
    }

    /** What every document of the merchant shares: market, merchant, trust, rating, place, pause, categories. */
    private ListingDocument.ListingDocumentBuilder base(
            MerchantFacts m, SearchLanguage lang, List<String> categoryIds) {
        var leaf = categoryIds.isEmpty() ? null : categoryIds.getFirst();
        return ListingDocument.builder()
                .market(Objects.requireNonNull(m.market()))
                .merchantId(m.id())
                .merchantName(m.name())
                .merchantType(Objects.requireNonNullElse(m.type(), ""))
                .merchantStatus(Objects.requireNonNullElse(m.status(), ""))
                .merchantSlug(m.slug())
                .categoryId(leaf)
                .categoryPath(categoryIds.stream()
                        .flatMap(c -> categories.path(c).stream())
                        .distinct()
                        .toList())
                .categoryRoot(categories.root(leaf))
                .categoryNames(categories.names(categoryIds, lang))
                .rating(m.rating())
                .reviewCount(m.reviewCount())
                .trustTier(m.tier())
                .trustRank(trustRank(m.tier()))
                .qualityScore(m.qualityScore())
                .vetting("approved")
                .status("live")
                .fulfilment(List.of())
                .openHours(List.of())
                .allergens(List.of())
                .dietary(List.of())
                .pausedUntil(m.pausedUntil())
                .location(m.lat() == null || m.lon() == null ? null : new GeoPoint(m.lat(), m.lon()))
                .serviceRadiusKm(m.radiusKm())
                .suggestCategory(
                        categoryIds.isEmpty()
                                ? null
                                : new Completion(
                                        inputs(categoryIds.stream()
                                                .map(c -> categories.name(c, lang))
                                                .toList()),
                                        0));
    }

    /** Completion weights: trust tier first, then rating, then recent sales. */
    private static ListingDocument finish(ListingDocument.ListingDocumentBuilder builder) {
        var draft = builder.suggest(new Completion(List.of(), 0)).build();
        var weight = draft.trustRank() * 100
                + (int) Math.round(Objects.requireNonNullElse(draft.rating(), 0.0) * 10)
                + Math.min(draft.sales30d(), 50);
        var category = draft.suggestCategory();
        return draft.toBuilder()
                .suggest(new Completion(inputs(List.of(draft.name())), weight))
                .suggestCategory(category == null ? null : new Completion(category.input(), weight))
                .build();
    }

    static int trustRank(@Nullable String tier) {
        return switch (tier == null ? "" : tier) {
            case "master" -> 3;
            case "trusted" -> 2;
            case "registered" -> 1;
            default -> 0;
        };
    }

    /**
     * Completion inputs: each text, and the text from each of its next words, so "sour" finds "Country sourdough" and
     * "mec" "Mobile mechanic" (the completion suggester only matches from the start of an input).
     */
    static List<String> inputs(List<String> texts) {
        var inputs = new LinkedHashSet<String>();
        for (var text : texts) {
            var words = Arrays.stream(text.strip().split("\\s+"))
                    .filter(w -> !w.isBlank())
                    .toList();
            for (var i = 0; i < Math.min(words.size(), MAX_SUGGEST_WORDS); i++) {
                var input = String.join(" ", words.subList(i, words.size()));
                if (words.get(i).chars().anyMatch(Character::isLetterOrDigit)) {
                    inputs.add(input.length() > 80 ? input.substring(0, 80) : input);
                }
            }
        }
        return List.copyOf(inputs);
    }
}
