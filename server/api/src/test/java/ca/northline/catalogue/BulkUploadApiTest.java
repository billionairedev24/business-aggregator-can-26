package ca.northline.catalogue;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Bulk upload (validate → report → import, history); the commerce integrations: {@link CommerceSyncApiTest}. */
class BulkUploadApiTest extends CatalogueApiTest {

    static final String IMPORTS = "/api/v1/merchants/{m}/listings/imports";

    ResultActions upload(Business biz, String template, String fileName, byte[] bytes) throws Exception {
        return mvc.perform(multipart(IMPORTS, biz.merchantId())
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes))
                .param("template", template)
                .with(TestJwt.member(biz.userId())));
    }

    static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Minimal .xlsx: one worksheet with inline-string and numeric cells. */
    static byte[] xlsx(List<List<Object>> rows) throws Exception {
        var sheet = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            var row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                var ref = (char) ('A' + c) + String.valueOf(r + 1);
                var value = row.get(c);
                if (value instanceof Number n) {
                    sheet.append("<c r=\"%s\"><v>%s</v></c>".formatted(ref, n));
                } else {
                    sheet.append("<c r=\"%s\" t=\"inlineStr\"><is><t>%s</t></is></c>".formatted(ref, value));
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheet.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Nested
    class Imports {

        @Test
        void validationReportsRowErrors_thenImportCreatesDraftsAndUpdatesExistingSkus() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            mvc.perform(postJson(
                                    "/api/v1/merchants/{m}/products",
                                    completeProduct("Wiper 22", "WB-22", 1900),
                                    biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isCreated());
            var file = csv("""
                    sku,title,gtin,brand,mpn,category_id,price,stock,part_type,length,position
                    WB-20,Wiper 20,,Bosch,20A,,17.00,12,Wiper blades,20 in,Front
                    WB-26,Wiper 26,028851200220,Bosch,26A,,21.00,5,Wiper blades,26 in,Front
                    ,No sku,,,,,10,1,Brakes,n/a,Front
                    OIL-0W20,Oil 0W-20,,,,,42,3,,n/a,n/a
                    WB-22,,,,,,19.50,30,,,
                    CBD-01,Grinder,,,,shop.restricted.cannabis-accessories,15,4,,,
                    WB-20,Duplicate,,,,,17,1,Wiper blades,20 in,Front
                    """);

            var report = json(upload(biz, "auto_parts", "wipers-sept.csv", file)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.fileName").value("wipers-sept.csv"))
                    .andExpect(jsonPath("$.status").value("validated"))
                    .andExpect(jsonPath("$.rowCount").value(7))
                    .andExpect(jsonPath("$.createCount").value(1))
                    .andExpect(jsonPath("$.updateCount").value(1))
                    .andExpect(jsonPath("$.errorCount").value(5))
                    .andExpect(jsonPath("$.errors[?(@.row == 3)].error").value("GTIN check digit invalid"))
                    .andExpect(jsonPath("$.errors[?(@.row == 4)].error").value("Missing SKU"))
                    .andExpect(jsonPath("$.errors[?(@.row == 5)].error")
                            .value("Category \"Auto parts\" requires attribute Part type"))
                    .andExpect(jsonPath("$.errors[?(@.row == 7)].error").value("Category not allowed in Shop"))
                    .andExpect(jsonPath("$.errors[?(@.row == 8)].error").value("Duplicate SKU in file")));

            // nothing changed yet
            mvc.perform(get("/api/v1/merchants/{m}/listings", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].priceCents").value(1900));

            mvc.perform(post(
                                    IMPORTS + "/{id}/commit",
                                    biz.merchantId(),
                                    report.get("id").asString())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("imported"))
                    .andExpect(jsonPath("$.importedAt").exists());
            mvc.perform(get("/api/v1/merchants/{m}/listings", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(
                            jsonPath("$.items[?(@.sku == 'WB-22')].priceCents").value(1950))
                    .andExpect(jsonPath("$.items[?(@.sku == 'WB-22')].stock").value(30))
                    .andExpect(jsonPath("$.items[?(@.sku == 'WB-20')].vetting").value("draft"))
                    .andExpect(
                            jsonPath("$.items[?(@.sku == 'WB-20')].priceCents").value(1700));

            mvc.perform(post(
                                    IMPORTS + "/{id}/commit",
                                    biz.merchantId(),
                                    report.get("id").asString())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict());
            mvc.perform(get(IMPORTS, biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].rowCount").value(7));
        }

        @Test
        void priceOutliersAreReportedAgainstTheCategoryMedian() throws Exception {
            var cat = "shop.electronics.phones-and-accessories";
            approvedComparable(cat, 5000);
            approvedComparable(cat, 5000);
            approvedComparable(cat, 5000);
            var biz = seller(MerchantRole.OWNER);
            upload(biz, "auto_parts", "p.csv", csv("""
                            sku,title,category_id,price,stock
                            BP-CER-R,Case,%s,4,10
                            """.formatted(cat)))
                    .andExpect(jsonPath("$.errors[0].error").value("Price $4 is 92% below category median — confirm"));
        }

        @Test
        void xlsxServicesTemplateCreatesServiceDrafts() throws Exception {
            var biz = provider(MerchantRole.TECHNICIAN);
            var file = xlsx(List.of(
                    List.of(
                            "sku",
                            "name",
                            "category_id",
                            "pricing_mode",
                            "price",
                            "duration_min",
                            "buffer_min",
                            "included",
                            "instant_book"),
                    List.of("SVC-BI", "Brake inspection", MECHANIC, "fixed", 89, 60, 20, "All four wheels", "yes"),
                    List.of("SVC-RB", "Engine rebuild", MECHANIC, "quote", "", 240, 0, "Quoted", "no"),
                    List.of("SVC-X", "Bad", MECHANIC, "barter", 10, 60, 0, "", "")));
            var report = json(upload(biz, "services", "services.xlsx", file)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.createCount").value(2))
                    .andExpect(jsonPath("$.errors[0].row").value(4))
                    .andExpect(jsonPath("$.errors[0].error").value("Pricing mode must be fixed, quote or hourly")));
            mvc.perform(post(
                                    IMPORTS + "/{id}/commit",
                                    biz.merchantId(),
                                    report.get("id").asString())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/merchants/{m}/listings", biz.merchantId()).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(
                            jsonPath("$.items[?(@.sku == 'SVC-BI')].priceCents").value(8900))
                    .andExpect(jsonPath("$.items[?(@.sku == 'SVC-RB')].pricingMode")
                            .value("quote"));
        }

        @Test
        void priceAndStockTemplateOnlyUpdatesKnownSkus() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            upload(biz, "price_stock", "price-update.csv", csv("sku;price;stock\nNOPE;10;1\n"))
                    .andExpect(jsonPath("$.errors[0].error").value("Unknown SKU — add it with a category template"));
        }

        @Test
        void fileProblemsAre422() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            upload(biz, "auto_parts", "notes.pdf", "hello".getBytes(StandardCharsets.UTF_8))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Upload an .xlsx or .csv file."));
            upload(biz, "auto_parts", "empty.csv", csv("sku,title\n"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("The file has no rows."));
            upload(biz, "", "a.csv", csv("sku\nA\n"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("template"));
        }

        @Test
        void bookkeeperSeesHistoryButCannotUpload() throws Exception {
            var biz = seller(MerchantRole.OWNER);
            var bookkeeper = member(biz.merchantId(), MerchantRole.BOOKKEEPER);
            mvc.perform(get(IMPORTS, biz.merchantId()).with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isOk());
            mvc.perform(multipart(IMPORTS, biz.merchantId())
                            .file(new MockMultipartFile("file", "a.csv", "text/csv", csv("sku\nA\n")))
                            .param("template", "price_stock")
                            .with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden());
        }
    }
}
