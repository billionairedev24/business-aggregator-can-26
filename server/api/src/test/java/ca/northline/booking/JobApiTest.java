package ca.northline.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.booking.api.BookingProgressed.BookingCompleted;
import ca.northline.booking.api.BookingProgressed.BookingEnRoute;
import ca.northline.booking.api.BookingProgressed.BookingOnSite;
import ca.northline.booking.api.BookingProgressed.BookingScopeChangeRequested;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Appointments: job list, job card, job flow (booking state machine), mid-job approvals, media upload. */
@RecordApplicationEvents
class JobApiTest extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    OperationsFixtures fx;
    Business biz;
    String customer;
    Instant today;

    @BeforeEach
    void setUp() {
        fx = new OperationsFixtures(jdbc);
        biz = data.business(MerchantRole.OWNER);
        customer = data.user("Amara Osei");
        today = Instant.now().truncatedTo(ChronoUnit.HOURS);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Nested
    class ListAndView {

        @Test
        void ownerSeesTheWeek_technicianOnlyOwnJobs() throws Exception {
            var tech = data.user("Jas Gill");
            data.member(biz.merchantId(), tech, MerchantRole.TECHNICIAN);
            fx.job(biz.merchantId(), biz.userId(), customer, today, "confirmed");
            fx.job(biz.merchantId(), tech, customer, today.plus(Duration.ofHours(2)), "confirmed");
            fx.job(biz.merchantId(), tech, customer, today.plus(Duration.ofDays(10)), "confirmed"); // outside range
            var from = today.minus(Duration.ofDays(1));
            var to = today.plus(Duration.ofDays(6));

            mvc.perform(get("/api/v1/merchants/{m}/jobs", biz.merchantId())
                            .param("from", from.toString())
                            .param("to", to.toString())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.items[0].title").value("Brake inspection"))
                    .andExpect(jsonPath("$.items[0].customerName").value("A. Osei"))
                    .andExpect(jsonPath("$.items[0].state").value("confirmed"))
                    .andExpect(jsonPath("$.items[1].memberName").value("Jas"));
            mvc.perform(get("/api/v1/merchants/{m}/jobs", biz.merchantId())
                            .param("from", from.toString())
                            .param("to", to.toString())
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].memberUserId").value(tech));
        }

        @Test
        void rangeLongerThan62DaysIs422() throws Exception {
            mvc.perform(get("/api/v1/merchants/{m}/jobs", biz.merchantId())
                            .param("from", today.toString())
                            .param("to", today.plus(Duration.ofDays(90)).toString())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("to"))
                    .andExpect(jsonPath("$.errors[0].message").value("Pick at most 62 days."));
        }

        @Test
        void jobCardShowsCustomerVehicleEscrowAndPastJobs() throws Exception {
            fx.job(biz.merchantId(), biz.userId(), customer, today.minus(Duration.ofDays(30)), "signed_off");
            var job = fx.job(biz.merchantId(), biz.userId(), customer, today, "confirmed");

            mvc.perform(get("/api/v1/merchants/{m}/jobs/{j}", biz.merchantId(), job)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customer.name").value("Amara Osei"))
                    .andExpect(jsonPath("$.customer.pastJobs").value(1))
                    .andExpect(jsonPath("$.vehicle").value("2018 Honda Civic"))
                    .andExpect(jsonPath("$.access").value("P2 stall 118"))
                    .andExpect(jsonPath("$.customerNote").value("Grinding on braking"))
                    .andExpect(jsonPath("$.escrow").value("held"))
                    .andExpect(jsonPath("$.priceCents").value(9345));
        }

        @Test
        void otherBusinessesJobsAreNotFound() throws Exception {
            var other = data.business(MerchantRole.OWNER);
            var job = fx.job(other.merchantId(), other.userId(), customer, today, "confirmed");

            mvc.perform(get("/api/v1/merchants/{m}/jobs/{j}", biz.merchantId(), job)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void nonMemberAndMissingMfaAreForbidden() throws Exception {
            var job = fx.job(biz.merchantId(), biz.userId(), customer, today, "confirmed");
            mvc.perform(get("/api/v1/merchants/{m}/jobs/{j}", biz.merchantId(), job)
                            .with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
            mvc.perform(get("/api/v1/merchants/{m}/jobs/{j}", biz.merchantId(), job)
                            .with(TestJwt.memberWithoutMfa(biz.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }
    }

    @Nested
    class Flow {

        @Test
        void confirmedToEnRouteToOnSiteToCompleted_logsProofAndPublishesEvents() throws Exception {
            var job = fx.job(biz.merchantId(), biz.userId(), customer, today, "confirmed");
            var base = "/api/v1/merchants/{m}/jobs/{j}/";

            mvc.perform(json(post(base + "en-route", biz.merchantId(), job), "{\"lat\":51.03,\"lng\":-114.08}")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("en_route"));
            mvc.perform(post(base + "on-site", biz.merchantId(), job).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("on_site"));

            var photo = upload();
            mvc.perform(json(
                                    post(base + "complete", biz.merchantId(), job),
                                    "{\"photoMediaIds\":[\"%s\"],\"report\":\"Pads at 3 mm, replaced.\"}"
                                            .formatted(photo))
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("completed"))
                    .andExpect(jsonPath("$.timeline", hasSize(3)))
                    .andExpect(jsonPath("$.timeline[2].type").value("completed"))
                    .andExpect(jsonPath("$.timeline[2].note").value("Pads at 3 mm, replaced."))
                    .andExpect(jsonPath("$.timeline[2].mediaId").value(photo));

            assertThat(events.stream(BookingEnRoute.class)).singleElement().satisfies(e -> {
                assertThat(e.aggregateId()).isEqualTo(job);
                assertThat(e.actorId()).isEqualTo(biz.userId());
            });
            assertThat(events.stream(BookingOnSite.class)).hasSize(1);
            assertThat(events.stream(BookingCompleted.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.photoCount()).isEqualTo(1));
            assertThat(jdbc.sql("select count(*) from booking.booking_events where booking_id = ? and geom is not null")
                            .param(job)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        }

        @Test
        void wrongStateIs409() throws Exception {
            var job = fx.job(biz.merchantId(), biz.userId(), customer, today, "confirmed");
            mvc.perform(post("/api/v1/merchants/{m}/jobs/{j}/on-site", biz.merchantId(), job)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("job_state"));
        }

        @Test
        void photoFromAnotherBusinessIs422() throws Exception {
            var job = fx.job(biz.merchantId(), biz.userId(), customer, today, "on_site");
            mvc.perform(json(
                                    post("/api/v1/merchants/{m}/jobs/{j}/complete", biz.merchantId(), job),
                                    "{\"photoMediaIds\":[\"01J9ZD3V00000000000000XXXX\"]}")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("photoMediaIds[0]"));
        }

        @Test
        void technicianCannotMoveSomeoneElsesJob_bookkeeperCannotOperate() throws Exception {
            var tech = data.user("Jas Gill");
            data.member(biz.merchantId(), tech, MerchantRole.TECHNICIAN);
            var bookkeeper = data.user("Priya");
            data.member(biz.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
            var job = fx.job(biz.merchantId(), biz.userId(), customer, today, "confirmed");

            mvc.perform(post("/api/v1/merchants/{m}/jobs/{j}/en-route", biz.merchantId(), job)
                            .with(TestJwt.member(tech)))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/merchants/{m}/jobs/{j}/en-route", biz.merchantId(), job)
                            .with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }
    }

    @Nested
    class Approvals {

        @Test
        void requestExtraPartsApproval() throws Exception {
            var job = fx.job(biz.merchantId(), biz.userId(), customer, today, "on_site");
            mvc.perform(json(
                                    post("/api/v1/merchants/{m}/jobs/{j}/approvals", biz.merchantId(), job),
                                    "{\"description\":\"Front rotors below spec\",\"amountCents\":14000}")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.state").value("pending"))
                    .andExpect(jsonPath("$.amountCents").value(14000));
            assertThat(events.stream(BookingScopeChangeRequested.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.amountCents()).isEqualTo(14000));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "{\"description\":\" \",\"amountCents\":100}   | description | Describe the extra parts or work.",
                    "{\"description\":\"Rotors\",\"amountCents\":0} | amountCents | Enter an amount.",
                    "{\"description\":\"Rotors\"}                   | amountCents | Enter an amount.",
                })
        void validationMessages(String body, String field, String message) throws Exception {
            var job = fx.job(biz.merchantId(), biz.userId(), customer, today, "on_site");
            mvc.perform(json(post("/api/v1/merchants/{m}/jobs/{j}/approvals", biz.merchantId(), job), body)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].message").value(message));
        }

        @Test
        void notAfterCompletion() throws Exception {
            var job = fx.job(biz.merchantId(), biz.userId(), customer, today, "completed");
            mvc.perform(json(
                                    post("/api/v1/merchants/{m}/jobs/{j}/approvals", biz.merchantId(), job),
                                    "{\"description\":\"Rotors\",\"amountCents\":100}")
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    class Media {

        @Test
        void rejectsOtherFileTypes() throws Exception {
            mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                            .file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", new byte[] {1}))
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Add a photo (JPEG, PNG, HEIC, WebP) or a PDF."));
        }
    }

    private String upload() throws Exception {
        var body = mvc.perform(multipart("/api/v1/merchants/{m}/media", biz.merchantId())
                        .file(new MockMultipartFile("file", "brakes.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("brakes.jpg"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }
}
