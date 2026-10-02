package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.ListingTexts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code catalogue.listing_texts} (V317). */
@Repository
@RequiredArgsConstructor
class ListingTextsJdbc implements ListingTexts {

    private final JdbcClient jdbc;

    @Override
    public Optional<Text> find(String listingId, String lang) {
        return jdbc.sql(
                        "select title, description from catalogue.listing_texts where listing_id = :id and lang = :lang")
                .param("id", listingId)
                .param("lang", lang)
                .query((rs, _) -> new Text(rs.getString("title"), rs.getString("description")))
                .optional();
    }

    @Override
    public Map<String, Text> findAll(Iterable<String> listingIds, String lang) {
        List<String> ids =
                StreamSupport.stream(listingIds.spliterator(), false).distinct().toList();
        var out = new HashMap<String, Text>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select listing_id, title, description from catalogue.listing_texts
                         where listing_id in (:ids) and lang = :lang
                        """).param("ids", ids).param("lang", lang).query(rs -> {
            out.put(rs.getString("listing_id"), new Text(rs.getString("title"), rs.getString("description")));
        });
        return out;
    }

    @Override
    public void save(String merchantId, String listingId, String lang, Text text, String actorId) {
        jdbc.sql("""
                        insert into catalogue.listing_texts (listing_id, merchant_id, lang, title, description, updated_at,
                                                             updated_by)
                        values (:id, :merchant, :lang, :title, :description, now(), :actor)
                        on conflict (listing_id, lang) do update
                           set title = excluded.title, description = excluded.description,
                               updated_at = excluded.updated_at, updated_by = excluded.updated_by
                        """)
                .param("id", listingId)
                .param("merchant", merchantId)
                .param("lang", lang)
                .param("title", text.title())
                .param("description", text.description())
                .param("actor", actorId)
                .update();
    }

    @Override
    public void delete(String listingId) {
        jdbc.sql("delete from catalogue.listing_texts where listing_id = :id")
                .param("id", listingId)
                .update();
    }
}
