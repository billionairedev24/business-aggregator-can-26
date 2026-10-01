package ca.northline.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.search.domain.Highlight;
import ca.northline.support.IntegrationTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The {@code test}/{@code local} default ({@code SEARCH_PROVIDER=local}): no Elasticsearch, every search empty, the
 * rules still enforced, anonymous callers welcome. Same context as the other integration tests.
 */
class SearchLocalProviderTest extends IntegrationTest {

    @Test
    void emptyResults_withoutAnIndex() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "sourdough"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.facets.kinds").isEmpty())
                .andExpect(jsonPath("$.next").doesNotExist());
        mvc.perform(get("/api/v1/search/suggest").param("q", "sour"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void theRulesStillApply() throws Exception {
        mvc.perform(get("/api/v1/search").param("sort", "distance"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("sort"))
                .andExpect(jsonPath("$.errors[0].rule").value("required"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Sorting by distance needs your location (lat and lng)."));
    }

    @Test
    void highlightsIgnoreCaseAndAccents_andStartAWord() {
        Assertions.assertThat(Highlight.of("Country sourdough", "SOUR")).containsExactly(new Highlight(8, 4));
        Assertions.assertThat(Highlight.of("Mécanicien mobile", "mec")).containsExactly(new Highlight(0, 3));
        Assertions.assertThat(Highlight.of("Pho dac biet", "ac")).isEmpty();
        Assertions.assertThat(Highlight.of("L'Épicerie", "epi")).containsExactly(new Highlight(2, 3));
    }
}
