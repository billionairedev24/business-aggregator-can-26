package ca.northline.catalogue.application;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: a listing's name and description in another language ({@code catalogue.listing_texts}, S-116). */
public interface ListingTexts {

    Optional<Text> find(String listingId, String lang);

    /** The texts of these listings in {@code lang}, by listing id (the consumer pages, one query). */
    Map<String, Text> findAll(Iterable<String> listingIds, String lang);

    void save(String merchantId, String listingId, String lang, Text text, String actorId);

    void delete(String listingId);

    /** @param description null = none written yet */
    record Text(String title, @Nullable String description) {

        public Text {
            title = title.strip();
            description = description == null || description.isBlank() ? null : description.strip();
        }

        /** Both the name and the description are there. */
        public boolean complete() {
            return !title.isEmpty() && description != null;
        }
    }
}
