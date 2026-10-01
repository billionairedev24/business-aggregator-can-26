package ca.northline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.catalogue.application.MediaStorage;
import ca.northline.food.application.KitchenPhotoStore;
import ca.northline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * S-77: under the {@code local} profile every seeded menu item and listing photo has bytes behind it (the bundled
 * sample pictures in {@code seed-media/}), so local development shows pictures instead of placeholders.
 */
@ActiveProfiles({"test", "local"})
class SeedPhotosTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MediaStorage media;

    @Autowired
    KitchenPhotoStore kitchenPhotos;

    @Test
    void everySeededMenuItemPhotoHasBytes() {
        var keys = jdbc.sql("select distinct photo_key from food.menu_items where photo_key like 'seed/%'")
                .query(String.class)
                .list();
        assertThat(keys).hasSizeGreaterThanOrEqualTo(8);
        assertThat(keys).allSatisfy(key -> assertThat(kitchenPhotos.get(key)).as(key).isPresent());
    }

    @Test
    void everySeededListingImageHasBytes() {
        var keys = jdbc.sql("select distinct url from catalogue.media where url like 'seed/%'")
                .query(String.class)
                .list();
        assertThat(keys).contains("seed/nl-p-88120-1.jpg", "seed/country-sourdough.jpg", "seed/brake-pads-ceramic.jpg");
        assertThat(keys).allSatisfy(key -> assertThat(media.get(key))
                .as(key)
                .hasValueSatisfying(bytes -> assertThat(bytes).hasSizeGreaterThan(1000)));
    }

    @Test
    void everyLiveSeededListingShowsAPicture() throws Exception {
        var withoutImage = jdbc.sql("""
                        select o.title from catalogue.offers o join catalogue.catalog_products p on p.id = o.product_id
                         where o.id like '01J9ZD3V%' and o.status = 'live' and o.vetting = 'approved'
                           and cardinality(o.own_images) = 0 and cardinality(p.image_set) = 0
                        """)
                .query(String.class)
                .list();
        assertThat(withoutImage).isEmpty();
        mvc.perform(get("/api/v1/public/catalogue/media/{id}", "01J9ZD3V000000000000SMED01"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }
}
