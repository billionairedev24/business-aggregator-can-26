package ca.northline.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.availability.api.AvailabilityChanged;
import ca.northline.availability.domain.AlbertaHolidays;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Availability: weekly hours + preview, booking rules, time off &amp; holidays, calendar sync &amp; team. */
@RecordApplicationEvents
class AvailabilityApiTest extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    Business biz;
    String tech;
    LocalDate today;

    @BeforeEach
    void setUp() {
        biz = data.business(MerchantRole.OWNER);
        tech = data.user("Jas Gill");
        data.member(biz.merchantId(), tech, MerchantRole.TECHNICIAN);
        today = LocalDate.now(ZONE);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body, String user) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body).with(TestJwt.member(user));
    }

    private String path(String rest) {
        return "/api/v1/merchants/" + biz.merchantId() + "/availability" + rest;
    }

    @Nested
    class Hours {

        @Test
        void saveSplitShiftHours_thenReadThemBack() throws Exception {
            mvc.perform(json(put(path("/hours")), """
                                    {"effectiveFrom":"%s","members":[{"memberUserId":"%s",
                                      "days":{"mon":[["08:00","16:00"]],"sat":[["17:00","20:00"],["10:00","15:00"]]}}]}
                                    """.formatted(today, tech), biz.userId()))
                    .andExpect(status().isOk());

            mvc.perform(get(path("/hours")).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members", hasSize(2)))
                    .andExpect(jsonPath("$.members[?(@.userId == '%s')].days.sat[0][0]".formatted(tech))
                            .value(org.hamcrest.Matchers.contains("10:00")))
                    .andExpect(jsonPath("$.members[?(@.userId == '%s')].days.sun".formatted(tech))
                            .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.empty())))
                    .andExpect(jsonPath("$.lastSavedAt").exists());
            assertThat(events.stream(AvailabilityChanged.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.what()).isEqualTo("hours"));
        }

        @Test
        void invalidRangesAre422WithTheMemberPath() throws Exception {
            mvc.perform(json(put(path("/hours")), """
                                    {"effectiveFrom":"%s","members":[{"memberUserId":"%s",
                                      "days":{"mon":[["16:00","08:00"]],"tue":[["08:00","12:00"],["11:00","14:00"]]}}]}
                                    """.formatted(today, tech), biz.userId()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors", hasSize(2)))
                    .andExpect(jsonPath("$.errors[0].field").value("members[0].days.mon[0]"))
                    .andExpect(jsonPath("$.errors[0].message").value("End time must be after start time."))
                    .andExpect(jsonPath("$.errors[1].field").value("members[0].days.tue[1]"))
                    .andExpect(jsonPath("$.errors[1].message")
                            .value("These hours overlap another range on the same day."));
        }

        @Test
        void effectiveDateInThePastIs422() throws Exception {
            mvc.perform(json(
                            put(path("/hours")),
                            "{\"effectiveFrom\":\"%s\",\"members\":[{\"memberUserId\":\"%s\",\"days\":{}}]}"
                                    .formatted(today.minusDays(3), tech),
                            biz.userId()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Pick today or a later date."));
        }

        @Test
        void bookkeeperCannotEditHours() throws Exception {
            var bookkeeper = data.user("Priya");
            data.member(biz.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
            mvc.perform(json(
                            put(path("/hours")),
                            "{\"effectiveFrom\":\"%s\",\"members\":[]}".formatted(today),
                            bookkeeper))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path("/hours")).with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path("/hours")).with(TestJwt.memberWithoutMfa(biz.userId())))
                    .andExpect(status().isForbidden());
        }

        @Test
        void previewIsHoursMinusJobsMinusBuffer() throws Exception {
            var day = today.plusDays(7);
            var customer = data.user("Customer");
            new OperationsFixtures(jdbc)
                    .job(
                            biz.merchantId(),
                            tech,
                            customer,
                            day.atTime(9, 0).atZone(ZONE).toInstant(),
                            "confirmed"); // 9:00–9:45
            mvc.perform(json(post(path("/preview")), """
                                    {"memberUserId":"%s","date":"%s","durationMin":45,"ranges":[["08:00","12:00"]],
                                     "intervalMin":30,"bufferMin":20}
                                    """.formatted(tech, day), biz.userId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobs").value(1))
                    .andExpect(jsonPath("$.slots[*].start")
                            .value(org.hamcrest.Matchers.contains(
                                    "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00")))
                    .andExpect(jsonPath("$.slots[*].free")
                            .value(org.hamcrest.Matchers.contains(false, false, false, false, false, true, true)));
        }
    }

    @Nested
    class Rules {

        @Test
        void saveAndReadRules() throws Exception {
            mvc.perform(json(put(path("/rules")), """
                                    {"intervalMin":15,"bufferMin":30,"minNoticeMin":0,"sameDayCutoffMin":540,"horizonDays":28,
                                     "maxJobsPerDay":4,"acceptMode":"approve","rescheduleFreeMin":720,"lateCancelFeeBps":5000,
                                     "emergencyPremiumCents":2500,"serviceAreas":["Beltline","Okotoks"]}
                                    """, biz.userId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.acceptMode").value("approve"))
                    .andExpect(jsonPath("$.serviceAreas", hasSize(2)));
            mvc.perform(get(path("/rules")).with(TestJwt.member(tech)))
                    .andExpect(jsonPath("$.intervalMin").value(15))
                    .andExpect(jsonPath("$.lateCancelFeeBps").value(5000))
                    .andExpect(jsonPath("$.zones", hasSize(9)));
        }

        @Test
        void valuesOutsideTheOptionsAre422() throws Exception {
            mvc.perform(json(put(path("/rules")), """
                                    {"intervalMin":20,"bufferMin":30,"minNoticeMin":60,"horizonDays":28,"maxJobsPerDay":4,
                                     "acceptMode":"instant","rescheduleFreeMin":720,"lateCancelFeeCents":0,"serviceAreas":["Mars"]}
                                    """, biz.userId()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[*].field")
                            .value(org.hamcrest.Matchers.contains("intervalMin", "serviceAreas")))
                    .andExpect(jsonPath("$.errors[0].message").value("Choose one of the options."));
        }
    }

    @Nested
    class TimeOff {

        @Test
        void addListConflictsRemove() throws Exception {
            var day = today.plusDays(5);
            new OperationsFixtures(jdbc)
                    .job(
                            biz.merchantId(),
                            tech,
                            data.user("C"),
                            day.atTime(10, 0).atZone(ZONE).toInstant(),
                            "confirmed");
            mvc.perform(get(path("/time-off/conflicts"))
                            .param("from", day.toString())
                            .param("to", day.plusDays(1).toString())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.bookings").value(1));

            var created = mvc.perform(
                            json(post(path("/time-off")), """
                                    {"memberUserId":"%s","startsOn":"%s","endsOn":"%s","kind":"closed","reason":"Training"}
                                    """.formatted(tech, day, day.plusDays(4)), biz.userId()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.memberName").value("Jas Gill"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString()
                    .replaceFirst("^\\{\"id\":\"([^\"]+)\".*", "$1");

            mvc.perform(get(path("/time-off")).with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.entries", hasSize(1)))
                    .andExpect(jsonPath("$.holidays", hasSize(5)))
                    .andExpect(jsonPath("$.holidayPremiumCents").value(5000));

            mvc.perform(delete(path("/time-off/" + created)).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNoContent());
            mvc.perform(delete(path("/time-off/" + created)).with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void validationMessages() throws Exception {
            mvc.perform(json(post(path("/time-off")), "{\"kind\":\"closed\"}", biz.userId()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("startsOn"))
                    .andExpect(jsonPath("$.errors[0].message").value("Pick the first day."));
            mvc.perform(json(
                            post(path("/time-off")),
                            "{\"startsOn\":\"%s\",\"endsOn\":\"%s\",\"kind\":\"special\"}"
                                    .formatted(today.plusDays(3), today.plusDays(1)),
                            biz.userId()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[*].message")
                            .value(org.hamcrest.Matchers.containsInAnyOrder(
                                    "The last day can't be before the first day.", "Add the hours you're open.")));
        }

        @Test
        void openAHoliday() throws Exception {
            var holiday = AlbertaHolidays.upcoming(today, 1).getFirst();
            mvc.perform(json(put(path("/holidays/" + holiday.date())), "{\"open\":true}", biz.userId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.open").value(true))
                    .andExpect(jsonPath("$.key").value(holiday.key()));
            mvc.perform(json(
                            put(path("/holidays/" + today.withMonth(6).withDayOfMonth(3))),
                            "{\"open\":true}",
                            biz.userId()))
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Nested
    class Sync {

        /** iCal connects at once; Google / Outlook start OAuth (S-32 — the flow itself: CalendarSyncApiTest). */
        @Test
        void connectIcalAtOnce_googleStartsOAuth_thenDisconnect() throws Exception {
            mvc.perform(post(path("/calendars/ical")).with(TestJwt.member(tech)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.connected").value(true))
                    .andExpect(jsonPath("$.feedUrl")
                            .value(org.hamcrest.Matchers.startsWith("webcal://northline.ca/cal/")));
            mvc.perform(post(path("/calendars/google")).with(TestJwt.member(tech)))
                    .andExpect(jsonPath("$.connected").value(false))
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.authorizationUrl")
                            .value(org.hamcrest.Matchers.containsString("/api/v1/calendar/oauth/google/callback")));
            mvc.perform(get(path("/sync")).with(TestJwt.member(tech)))
                    .andExpect(jsonPath("$.calendars[0].provider").value("google"))
                    .andExpect(jsonPath("$.calendars[0].connected").value(false))
                    .andExpect(jsonPath("$.calendars[2].provider").value("ical"))
                    .andExpect(jsonPath("$.calendars[2].connected").value(true))
                    .andExpect(jsonPath("$.team", hasSize(2)));
            mvc.perform(delete(path("/calendars/ical")).with(TestJwt.member(tech)))
                    .andExpect(jsonPath("$.connected").value(false));
            mvc.perform(post(path("/calendars/myspace")).with(TestJwt.member(tech)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void onlyTheOwnerHidesMembers() throws Exception {
            mvc.perform(json(put(path("/team/" + tech)), "{\"bookable\":false}", tech))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(json(put(path("/team/" + tech)), "{\"bookable\":false}", biz.userId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookable").value(false));
            assertThat(jdbc.sql("select bookable from merchants.merchant_members where merchant_id = ? and user_id = ?")
                            .params(biz.merchantId(), tech)
                            .query(Boolean.class)
                            .single())
                    .isFalse();
        }
    }
}
