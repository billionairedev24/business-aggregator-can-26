package ca.northline.hire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.booking.api.QuoteRequested;
import ca.northline.shared.storage.VirusScanner;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-153: photos on quote requests and on a booking's job details — uploaded to object storage through the platform's
 * scanner hook (a test scanner here that refuses EICAR-marked files), type and size checked by declared type and
 * signature, readable by their owner and by the businesses the request went to (or the booking's), nobody else;
 * {@code quote.requested} carries their count, never the photos.
 */
@RecordApplicationEvents
@Import(CustomerPhotosApiTest.Scanner.class)
class CustomerPhotosApiTest extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration
    static class Scanner {
        @Bean
        VirusScanner eicarScanner() {
            return (_, _, bytes) -> new String(bytes, StandardCharsets.ISO_8859_1).contains("EICAR")
                    ? new VirusScanner.Verdict.Infected("EICAR-Test-File")
                    : new VirusScanner.Verdict.Clean();
        }
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    HireFixtures fx;
    HireFixtures.Provider wrench;
    HireFixtures.Provider other;
    String customer;

    @BeforeEach
    void setUp() {
        new CategorySeeder(dataSource).seed();
        fx = new HireFixtures(jdbc);
        wrench = fx.provider("Photo Wrench", "master", List.of("Beltline"));
        other = fx.provider("Photo Other", "master", List.of("Beltline"));
        for (var p : List.of(wrench, other)) {
            fx.service(p.merchantId(), BookingFlow.MECHANIC, "Alternator / starter", "quote", null, 120);
        }
        customer = data.user("Amara Osei");
    }

    static byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    String upload(String who, String name, String type, byte[] bytes) throws Exception {
        var body = mvc.perform(multipart("/api/v1/me/photos")
                        .file(new MockMultipartFile("file", name, type, bytes))
                        .with(TestJwt.customer(who)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    String request(List<String> photoIds) throws Exception {
        var r = new LinkedHashMap<String, Object>();
        r.put("category", "mobile-mechanic");
        r.put("providers", List.of(wrench.slug()));
        r.put("description", "Dent in the rear door, see the photos.");
        r.put("vehicle", Map.of("year", "2018", "make", "Honda", "model", "Civic"));
        r.put("photoIds", photoIds);
        var body = mvc.perform(post("/api/v1/me/quote-requests")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(r)))
                .andReturn()
                .getResponse();
        return body.getStatus() == 201
                ? JsonPath.read(body.getContentAsString(), "$.requestId")
                : body.getContentAsString();
    }

    @Test
    void aPhotoGoesWithTheRequest_theProviderAskedSeesIt_nobodyElse() throws Exception {
        var bytes = png();
        var photo = upload(customer, "dent.png", "image/png", bytes);
        mvc.perform(get("/api/v1/me/photos/{id}", photo).with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(bytes));
        mvc.perform(get("/api/v1/me/photos/{id}", photo).with(TestJwt.customer(data.user("Nosy"))))
                .andExpect(status().isNotFound());

        var requestId = request(List.of(photo));
        assertThat(events.stream(QuoteRequested.class)
                        .filter(e -> e.aggregateId().equals(requestId)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.photoCount()).isEqualTo(1);
                    assertThat(e.merchantIds()).containsExactly(wrench.merchantId());
                    assertThat(e.quoteRef()).startsWith("QT-");
                    assertThat(e.toString()).doesNotContain("Dent", "Amara", "Beltline");
                });
        mvc.perform(get("/api/v1/me/quote-requests/{id}", requestId).with(TestJwt.customer(customer)))
                .andExpect(jsonPath("$.photos[0].id").value(photo));
        mvc.perform(get("/api/v1/merchants/{m}/quote-requests", wrench.merchantId())
                        .with(TestJwt.member(wrench.owner())))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].photoIds[0]".formatted(requestId))
                        .value(photo));
        mvc.perform(get("/api/v1/merchants/{m}/customer-photos/{p}", wrench.merchantId(), photo)
                        .with(TestJwt.member(wrench.owner())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
        mvc.perform(get("/api/v1/merchants/{m}/customer-photos/{p}", other.merchantId(), photo)
                        .with(TestJwt.member(other.owner())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/merchants/{m}/customer-photos/{p}", wrench.merchantId(), photo)
                        .with(TestJwt.memberWithoutMfa(wrench.owner())))
                .andExpect(status().isForbidden());

        // a photo goes with one job only
        assertThat(request(List.of(photo))).contains("That photo is no longer available. Add it again.");
    }

    @Test
    void typeSizeSignatureAndCountAreChecked_andTheScannerHasTheLastWord() throws Exception {
        var path = "/api/v1/me/photos";
        mvc.perform(multipart(path)
                        .file(new MockMultipartFile(
                                "file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII)))
                        .with(TestJwt.customer(customer)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Add a photo (JPEG, PNG, HEIC or WebP)."));
        mvc.perform(multipart(path)
                        .file(new MockMultipartFile(
                                "file",
                                "fake.png",
                                "image/png",
                                "not a png at all".getBytes(StandardCharsets.US_ASCII)))
                        .with(TestJwt.customer(customer)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Add a photo (JPEG, PNG, HEIC or WebP)."));
        mvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .with(TestJwt.customer(customer)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a photo to upload."));
        var big = new byte[(int) (10L * 1024 * 1024) + 10];
        System.arraycopy(png(), 0, big, 0, 16);
        mvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "big.png", "image/png", big))
                        .with(TestJwt.customer(customer)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Photos can be up to 10 MB."));
        var infected = new ByteArrayOutputStream();
        infected.write(png());
        infected.write("EICAR".getBytes(StandardCharsets.ISO_8859_1));
        mvc.perform(multipart(path)
                        .file(new MockMultipartFile("file", "virus.png", "image/png", infected.toByteArray()))
                        .with(TestJwt.customer(customer)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].message").value("This file can't be accepted. Try a different file."));
        assertThat(jdbc.sql("select count(*) from booking.customer_photos where customer_id = ? and file_name = ?")
                        .params(customer, "virus.png")
                        .query(Long.class)
                        .single())
                .isZero();

        var seven = new java.util.ArrayList<String>();
        for (int i = 0; i < 7; i++) {
            seven.add(upload(customer, "p" + i + ".png", "image/png", png()));
        }
        assertThat(request(seven)).contains("Add up to 6 photos.");
        var someoneElses = upload(data.user("Someone"), "theirs.png", "image/png", png());
        assertThat(request(List.of(someoneElses))).contains("That photo is no longer available. Add it again.");
        mvc.perform(multipart(path).file(new MockMultipartFile("file", "x.png", "image/png", png())))
                .andExpect(status().isUnauthorized());
    }
}
