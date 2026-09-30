package ca.northline.searchindex;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The two languages of the read model (bilingual from day one): one index per language behind the alias
 * {@code listings_<code>}, each with its analyzers and its synonym set {@code listings-synonyms-<code>}.
 */
public enum SearchLanguage {
    EN,
    FR;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The alias every reader and writer uses; it points at one versioned index ({@link IndexLayout#newIndexName}). */
    public String alias() {
        return IndexLayout.FAMILY + "_" + code();
    }

    /** The Elasticsearch synonym set the language's search analyzer reads (reloadable, no reindex). */
    public String synonymsSet() {
        return IndexLayout.FAMILY + "-synonyms-" + code();
    }

    /** French for any {@code fr*} language tag (fr, fr-CA), English otherwise, including none. */
    public static SearchLanguage of(@Nullable String languageTag) {
        return languageTag != null && languageTag.toLowerCase(Locale.ROOT).startsWith("fr") ? FR : EN;
    }
}
