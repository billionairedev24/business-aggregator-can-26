package ca.northline.catalogue;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * S-72: bulk import checks image URLs (reachable JPG/PNG of at least 1000 px, under the S-33 SSRF rules) and stores
 * them as the business's own images; re-importing an existing SKU updates every column the row fills in, and the rows
 * of an existing parent update or add its variants. Image hosts are a WireMock on loopback (the test profile allows
 * http + loopback, never private or metadata addresses).
 */
class BulkReimportApiTest extends CatalogueApiTest {

    static final String IMPORTS = "/api/v1/merchants/{m}/listings/imports";
    static final WireMockServer IMAGES = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        IMAGES.start();
        IMAGES.stubFor(get(urlEqualTo("/img/main.png"))
                .willReturn(
                        aResponse().withHeader("Content-Type", "image/png").withBody(png(1200, 1200, Color.WHITE))));
        IMAGES.stubFor(get(urlEqualTo("/img/side.png"))
                .willReturn(
                        aResponse().withHeader("Content-Type", "image/png").withBody(png(1100, 1000, Color.WHITE))));
        IMAGES.stubFor(get(urlEqualTo("/img/small.png"))
                .willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(png(400, 400, Color.WHITE))));
        IMAGES.stubFor(get(urlEqualTo("/img/page.html"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<html>nope</html>")));
        IMAGES.stubFor(get(urlEqualTo("/img/moved.png"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "http://169.254.169.254/latest/")));
        IMAGES.stubFor(get(urlEqualTo("/img/gone.png")).willReturn(aResponse().withStatus(404)));
    }

    @AfterAll
    static void stop() {
        IMAGES.stop();
    }

    static String img(String name) {
        return IMAGES.baseUrl() + "/img/" + name;
    }

    ResultActions upload(Business biz, String template, String text) throws Exception {
        return mvc.perform(multipart(IMPORTS, biz.merchantId())
                .file(new MockMultipartFile(
                        "file", template + ".csv", "text/csv", text.getBytes(StandardCharsets.UTF_8)))
                .param("template", template)
                .with(TestJwt.member(biz.userId())));
    }

    void commit(Business biz, JsonNode report) throws Exception {
        mvc.perform(post(
                                IMPORTS + "/{id}/commit",
                                biz.merchantId(),
                                report.get("id").asString())
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());
    }

    String idOf(Business biz, String sku) throws Exception {
        var items = json(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/merchants/{m}/listings", biz.merchantId())
                        .with(TestJwt.member(biz.userId()))))
                .get("items");
        for (var item : items) {
            if (sku.equals(item.get("sku").asString())) {
                return item.get("id").asString();
            }
        }
        throw new AssertionError("no listing " + sku);
    }

    ResultActions listing(Business biz, String id) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/merchants/{m}/listings/{id}", biz.merchantId(), id)
                .with(TestJwt.member(biz.userId())));
    }

    @Nested
    class ImageUrls {

        @Test
        void unreachableRefusedOrUnusableImagesAreReported_validOnesBecomeTheListingsImages() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var header = "sku,title,brand,price,stock,part_type,length,position,image_urls\n";
            var row = "%s,Wiper %s,Bosch,19,5,Wiper blades,22 in,Front,%s\n";
            var report = json(upload(
                            biz,
                            "auto_parts",
                            header
                                    + row.formatted("IMG-OK", "ok", img("main.png") + " " + img("side.png"))
                                    + row.formatted("IMG-404", "404", img("gone.png"))
                                    + row.formatted("IMG-SMALL", "small", img("small.png"))
                                    + row.formatted("IMG-HTML", "html", img("page.html"))
                                    + row.formatted("IMG-REDIRECT", "redirect", img("moved.png"))
                                    + row.formatted("IMG-PRIVATE", "private", "http://10.1.2.3/a.png")
                                    + row.formatted("IMG-META", "meta", "http://169.254.169.254/latest/")
                                    + row.formatted("IMG-BAD", "bad", "not-a-link"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.createCount").value(1))
                    .andExpect(jsonPath("$.errorCount").value(7))
                    .andExpect(jsonPath("$.errors[?(@.sku == 'IMG-404')].error").value("Image URL unreachable"))
                    .andExpect(jsonPath("$.errors[?(@.sku == 'IMG-REDIRECT')].error")
                            .value("Image URL unreachable"))
                    .andExpect(jsonPath("$.errors[?(@.sku == 'IMG-SMALL')].error")
                            .value("Image URL is under 1000 px on the longest side"))
                    .andExpect(jsonPath("$.errors[?(@.sku == 'IMG-HTML')].error")
                            .value("Image URL is not a JPG or PNG image"))
                    .andExpect(jsonPath("$.errors[?(@.sku == 'IMG-PRIVATE')].error")
                            .value("Image URL must be a public https:// link"))
                    .andExpect(jsonPath("$.errors[?(@.sku == 'IMG-META')].error")
                            .value("Image URL must be a public https:// link"))
                    .andExpect(jsonPath("$.errors[?(@.sku == 'IMG-BAD')].error").value("Image URL is not a valid link"))
                    .andExpect(jsonPath("$.errors[0].row").value(3))); // the report stays in row order
            commit(biz, report);
            listing(biz, idOf(biz, "IMG-OK"))
                    .andExpect(jsonPath("$.imageSource").value("own"))
                    .andExpect(jsonPath("$.images.length()").value(2))
                    .andExpect(jsonPath("$.images[0].width").value(1200));
        }

        @Test
        void aRowHoldsAtMostNineImages() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var ten = String.join(" ", java.util.Collections.nCopies(10, img("main.png")));
            upload(
                            biz,
                            "auto_parts",
                            "sku,title,price,stock,part_type,length,position,image_urls\nTEN,Ten,19,5,Wiper blades,22 in,Front,"
                                    + ten + "\n")
                    .andExpect(jsonPath("$.errors[0].error").value("Up to 9 image URLs per row"));
        }
    }

    @Nested
    class FullUpdates {

        @Test
        void reimportingAnSkuUpdatesEveryColumnTheRowFillsIn_andKeepsTheEmptyOnes() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var created = json(upload(biz, "auto_parts", """
                            sku,title,brand,mpn,price,stock,part_type,length,position
                            RE-1,Wiper 22,Bosch,22A,19,5,Wiper blades,22 in,Front
                            """)
                    .andExpect(jsonPath("$.createCount").value(1)));
            commit(biz, created);
            var id = idOf(biz, "RE-1");

            var report = json(upload(
                            biz,
                            "auto_parts",
                            "sku,title,brand,mpn,price,stock,part_type,length,position,image_urls\n"
                                    + "RE-1,Wiper 22 · all-season,,22B,21.50,,,26 in,," + img("main.png") + "\n")
                    .andExpect(jsonPath("$.updateCount").value(1))
                    .andExpect(jsonPath("$.errorCount").value(0)));
            commit(biz, report);
            listing(biz, id)
                    .andExpect(jsonPath("$.title").value("Wiper 22 · all-season"))
                    .andExpect(jsonPath("$.brand").value("Bosch")) // empty cell: kept
                    .andExpect(jsonPath("$.mpn").value("22B"))
                    .andExpect(jsonPath("$.priceCents").value(2150))
                    .andExpect(jsonPath("$.stock").value(5)) // empty cell: kept
                    .andExpect(jsonPath("$.attributes.length").value("26 in"))
                    .andExpect(jsonPath("$.attributes.partType").value("Wiper blades"))
                    .andExpect(jsonPath("$.images.length()").value(1))
                    .andExpect(jsonPath("$.imageSource").value("own"));
        }

        @Test
        void servicesAreUpdatedToo() throws Exception {
            var biz = provider(MerchantRole.OWNER);
            var created = json(upload(
                    biz,
                    "services",
                    "sku,name,category_id,pricing_mode,price,duration_min,buffer_min,included,instant_book\n"
                            + "SV-1,Brake check," + MECHANIC + ",fixed,89,60,20,Pads and rotors,yes\n"));
            commit(biz, created);
            var report = json(upload(biz, "services", """
                            sku,name,category_id,pricing_mode,price,duration_min,buffer_min,included,instant_book
                            SV-1,Brake inspection,,,99,90,,,no
                            """)
                    .andExpect(jsonPath("$.updateCount").value(1)));
            commit(biz, report);
            listing(biz, idOf(biz, "SV-1"))
                    .andExpect(jsonPath("$.name").value("Brake inspection"))
                    .andExpect(jsonPath("$.priceCents").value(9900))
                    .andExpect(jsonPath("$.durationMin").value(90))
                    .andExpect(jsonPath("$.bufferMin").value(20))
                    .andExpect(jsonPath("$.included").value("Pads and rotors"))
                    .andExpect(jsonPath("$.instantBook").value(false))
                    .andExpect(jsonPath("$.categoryId").value(MECHANIC));
        }

        @Test
        void rowsOfAnExistingParentUpdateItsVariantsOrAddOne() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var header = "parent_sku,sku,title,price,stock,department,material,size,colour\n";
            var created = json(upload(
                            biz,
                            "clothing",
                            header + "PK-1,PK-1-M,Down parka,289,4,Unisex,Blend,M,Black\n"
                                    + "PK-1,PK-1-L,Down parka,289,2,Unisex,Blend,L,Black\n")
                    .andExpect(jsonPath("$.createCount").value(2)));
            commit(biz, created);
            var id = idOf(biz, "PK-1");

            var report = json(upload(
                            biz,
                            "clothing",
                            header + "PK-1,PK-1-M,Down parka · winter,269,,,,,\n"
                                    + "PK-1,PK-1-XL,,299,1,,,XL,Black\n"
                                    + "PK-1,PK-1-S,,,,,,S,Black\n")
                    .andExpect(jsonPath("$.updateCount").value(2))
                    .andExpect(jsonPath("$.errors[0].sku").value("PK-1-S"))
                    .andExpect(jsonPath("$.errors[0].error").value("Missing price")));
            commit(biz, report);
            listing(biz, id)
                    .andExpect(jsonPath("$.title").value("Down parka · winter"))
                    .andExpect(jsonPath("$.variants.length()").value(3))
                    .andExpect(jsonPath("$.variants[?(@.sku == 'PK-1-M')].priceCents")
                            .value(26900))
                    .andExpect(
                            jsonPath("$.variants[?(@.sku == 'PK-1-M')].stock").value(4))
                    .andExpect(jsonPath("$.variants[?(@.sku == 'PK-1-L')].priceCents")
                            .value(28900))
                    .andExpect(
                            jsonPath("$.variants[?(@.sku == 'PK-1-XL')].value").value("XL · Black"))
                    .andExpect(jsonPath("$.priceCents").value(26900))
                    .andExpect(jsonPath("$.stock").value(7));
        }
    }
}
