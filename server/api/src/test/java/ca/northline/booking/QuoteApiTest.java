package ca.northline.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.booking.api.QuoteSent;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** Quote requests, the itemized quote composer, revisions, and the DB triggers that keep sent quotes honest. */
@RecordApplicationEvents
class QuoteApiTest extends IntegrationTest {

    static final String ALTERNATOR = """
            {"lines":[
               {"kind":"labour","description":"Diagnose charging system","qty":0.5,"unitCents":6500},
               {"kind":"part","description":"Alternator — remanufactured, 12-mo warranty","qty":1,"unitCents":24000},
               {"kind":"labour","description":"Replace alternator & serpentine belt check","qty":1.5,"unitCents":13000},
               {"kind":"discount","description":"Returning customer","qty":1,"unitCents":1000}],
             "scope":"Confirm charging fault, replace alternator.","exclusions":"Belt extra if worn.",
             "durationMin":120,"validHours":72,"warranty":"parts_labour_12m","depositKind":"parts_upfront"}
            """;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    OperationsFixtures fx;
    Business biz;
    String customer;
    String request;

    @BeforeEach
    void setUp() {
        fx = new OperationsFixtures(jdbc);
        biz = data.business(MerchantRole.OWNER);
        customer = data.user("Minh Tran");
        request = fx.quoteRequest(biz.merchantId(), customer);
    }

    private MockHttpServletRequestBuilder send(String body) {
        return post("/api/v1/merchants/{m}/quote-requests/{r}/quotes", biz.merchantId(), request)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(TestJwt.member(biz.userId()));
    }

    private String sendAlternator() throws Exception {
        var body = mvc.perform(send(ALTERNATOR))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return body.replaceFirst("^\\{\"id\":\"([^\"]+)\".*", "$1");
    }

    @Nested
    class Requests {

        @Test
        void listsOpenRequestsWithCustomerAndRef() throws Exception {
            mvc.perform(get("/api/v1/merchants/{m}/quote-requests", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].title").value("Alternator, 2016 Civic"))
                    .andExpect(jsonPath("$.items[0].customerName").value("M. Tran"))
                    .andExpect(jsonPath("$.items[0].ref").value(org.hamcrest.Matchers.startsWith("QT-")))
                    .andExpect(jsonPath("$.items[0].quote").doesNotExist());
        }

        @Test
        void declineRemovesItFromTheList() throws Exception {
            mvc.perform(post("/api/v1/merchants/{m}/quote-requests/{r}/decline", biz.merchantId(), request)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/merchants/{m}/quote-requests", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }

        @Test
        void bookkeeperCannotQuote_strangerCannotRead() throws Exception {
            var bookkeeper = data.user("Priya");
            data.member(biz.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER);
            mvc.perform(post("/api/v1/merchants/{m}/quote-requests/{r}/quotes", biz.merchantId(), request)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ALTERNATOR)
                            .with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(get("/api/v1/merchants/{m}/quote-requests", biz.merchantId())
                            .with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/merchants/{m}/quote-requests", biz.merchantId())
                            .with(TestJwt.memberWithoutMfa(biz.userId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }
    }

    @Nested
    class Send {

        @Test
        void sendsItemizedQuote_totalsByKind_gst_depositAndEvent() throws Exception {
            mvc.perform(send(ALTERNATOR))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.state").value("sent"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.lines", hasSize(4)))
                    .andExpect(jsonPath("$.lines[0].amountCents").value(3250))
                    .andExpect(jsonPath("$.lines[3].amountCents").value(-1000))
                    .andExpect(jsonPath("$.labourCents").value(22750))
                    .andExpect(jsonPath("$.partsCents").value(24000))
                    .andExpect(jsonPath("$.discountCents").value(1000))
                    .andExpect(jsonPath("$.subtotalCents").value(45750))
                    .andExpect(jsonPath("$.taxBps").value(500))
                    .andExpect(jsonPath("$.taxCents").value(2288))
                    .andExpect(jsonPath("$.totalCents").value(48038))
                    .andExpect(jsonPath("$.depositCents").value(25200))
                    .andExpect(jsonPath("$.validUntil").exists())
                    .andExpect(jsonPath("$.exclusions").value("Belt extra if worn."));
            assertThat(events.stream(QuoteSent.class)).singleElement().satisfies(e -> {
                assertThat(e.quoteVersion()).isEqualTo(1);
                assertThat(e.totalCents()).isEqualTo(48038);
                assertThat(e.supersededQuoteId()).isNull();
            });
        }

        @Test
        void secondSendIs409_reviseInstead() throws Exception {
            sendAlternator();
            mvc.perform(send(ALTERNATOR))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("quote_already_sent"));
        }

        @Test
        void usesTheExistingDraft() throws Exception {
            var draft = Ids.next();
            jdbc.sql("""
                            insert into booking.quotes (id, request_id, merchant_id, ref, version, scope, warranty, deposit_kind,
                                   subtotal_cents, tax_cents, total_cents, valid_hours, state)
                            values (?, ?, ?, 'QT-1', 1, 'draft scope', 'none', 'none', 0, 0, 0, 72, 'draft')
                            """).params(draft, request, biz.merchantId()).update();
            mvc.perform(send(ALTERNATOR))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(draft))
                    .andExpect(jsonPath("$.state").value("sent"));
        }

        /** validation-rules.md § Quote — exact messages. */
        @ParameterizedTest(name = "[{index}] {1}")
        @CsvSource(
                delimiter = '|',
                quoteCharacter = '`',
                value = {
                    "{\"lines\":[{\"kind\":\"labour\",\"description\":\" \",\"qty\":1,\"unitCents\":100}],\"scope\":\"x\"}"
                            + " | lines[0].description | Describe this line — customers must see what they're paying for.",
                    "{\"lines\":[{\"kind\":\"part\",\"description\":\"Pads\",\"qty\":1,\"unitCents\":0}],\"scope\":\"x\"}"
                            + " | lines[0].unitCents | Enter an amount.",
                    "{\"lines\":[{\"kind\":\"part\",\"description\":\"Pads\",\"qty\":1}],\"scope\":\"x\"}"
                            + " | lines[0].unitCents | Enter an amount.",
                    "{\"lines\":[{\"kind\":\"labour\",\"description\":\"Diag\",\"qty\":1,\"unitCents\":100}],\"scope\":\"  \"}"
                            + " | scope | Describe the scope of work.",
                    "{\"lines\":[],\"scope\":\"x\"} | lines | Add at least one line.",
                    "{\"lines\":[{\"kind\":\"labour\",\"description\":\"Diag\",\"qty\":0,\"unitCents\":100}],\"scope\":\"x\"}"
                            + " | lines[0].qty | Quantity must be more than 0.",
                    "{\"lines\":[{\"kind\":\"labour\",\"description\":\"Diag\",\"qty\":1,\"unitCents\":100}],\"scope\":\"x\",\"validHours\":48}"
                            + " | validHours | Choose how long the quote is valid.",
                    "{\"lines\":[{\"kind\":\"labour\",\"description\":\"Diag\",\"qty\":1,\"unitCents\":100},{\"kind\":\"discount\",\"description\":\"Off\",\"qty\":1,\"unitCents\":500}],\"scope\":\"x\"}"
                            + " | lines | Discounts can't be more than the other lines.",
                })
        void validationMessages(String partial, String field, String message) throws Exception {
            var body = partial.substring(0, partial.length() - 1)
                    + (partial.contains("validHours") ? "" : ",\"validHours\":72")
                    + ",\"warranty\":\"none\",\"depositKind\":\"none\"}";
            mvc.perform(send(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].message")
                            .value(org.hamcrest.Matchers.contains(message)));
        }

        @Test
        void discountLineMayBeZero() throws Exception {
            mvc.perform(send("""
                            {"lines":[{"kind":"labour","description":"Diag","qty":1,"unitCents":100},
                                      {"kind":"discount","description":"Goodwill","qty":1,"unitCents":0}],
                             "scope":"x","validHours":24,"warranty":"none","depositKind":"none"}
                            """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.totalCents").value(105));
        }
    }

    @Nested
    class Revise {

        @Test
        void revisionIsVersion2_priorSuperseded_bothImmutable() throws Exception {
            var v1 = sendAlternator();
            mvc.perform(post("/api/v1/merchants/{m}/quotes/{q}/revisions", biz.merchantId(), v1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lines":[{"kind":"labour","description":"Diagnose only","qty":1,"unitCents":9000}],
                                     "scope":"Diagnosis","validHours":24,"warranty":"labour_90d","depositKind":"pct","depositBps":2500}
                                    """)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.state").value("sent"))
                    .andExpect(jsonPath("$.totalCents").value(9450))
                    .andExpect(jsonPath("$.depositCents").value(2363));

            mvc.perform(get("/api/v1/merchants/{m}/quotes/{q}", biz.merchantId(), v1)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.state").value("superseded"))
                    .andExpect(jsonPath("$.totalCents").value(48038));
            mvc.perform(get("/api/v1/merchants/{m}/quote-requests", biz.merchantId())
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(jsonPath("$.items[0].quote.version").value(2));
            assertThat(events.stream(QuoteSent.class))
                    .filteredOn(e -> e.quoteVersion() == 2)
                    .singleElement()
                    .satisfies(e -> assertThat(e.supersededQuoteId()).isEqualTo(v1));

            // superseded versions can't be revised again
            mvc.perform(post("/api/v1/merchants/{m}/quotes/{q}/revisions", biz.merchantId(), v1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ALTERNATOR)
                            .with(TestJwt.member(biz.userId())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("quote_state"));
        }
    }

    /** The V016 quote-total trigger and the V040 immutability triggers are never bypassed. */
    @Nested
    class Triggers {

        @Test
        void sentQuoteWhoseSubtotalDoesNotMatchItsLinesIsRejectedAtCommit() {
            var quote = Ids.next();
            assertThatThrownBy(() -> tx.executeWithoutResult(_ -> {
                        insertDraft(quote, 5000);
                        insertLine(quote, 0, 4000);
                        jdbc.sql("update booking.quotes set state = 'sent' where id = ?")
                                .param(quote)
                                .update();
                    }))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("does not match lines");
        }

        @Test
        void matchingSubtotalCommits() {
            var quote = Ids.next();
            tx.executeWithoutResult(_ -> {
                insertDraft(quote, 4000);
                insertLine(quote, 0, 4000);
                jdbc.sql("update booking.quotes set state = 'sent' where id = ?")
                        .param(quote)
                        .update();
            });
            assertThat(jdbc.sql("select state from booking.quotes where id = ?")
                            .param(quote)
                            .query(String.class)
                            .single())
                    .isEqualTo("sent");
        }

        @Test
        void linesOfASentQuoteCanNotChange() throws Exception {
            var quote = sendAlternator();
            assertThatThrownBy(() -> jdbc.sql(
                                    "update booking.quote_lines set unit_cents = 1, amount_cents = 1 where quote_id = ?")
                            .param(quote)
                            .update())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("can no longer be changed");
            assertThatThrownBy(() -> jdbc.sql("delete from booking.quote_lines where quote_id = ?")
                            .param(quote)
                            .update())
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertLine(quote, 9, 100)).isInstanceOf(DataAccessException.class);
        }

        @Test
        void contentOfASentQuoteCanNotChange() throws Exception {
            var quote = sendAlternator();
            assertThatThrownBy(() -> jdbc.sql(
                                    "update booking.quotes set total_cents = 1, subtotal_cents = 1, tax_cents = 0 where id = ?")
                            .param(quote)
                            .update())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("can no longer be changed");
            assertThatThrownBy(() -> jdbc.sql("update booking.quotes set scope = 'other' where id = ?")
                            .param(quote)
                            .update())
                    .isInstanceOf(DataAccessException.class);
        }

        private void insertDraft(String quote, long subtotal) {
            jdbc.sql("""
                            insert into booking.quotes (id, request_id, merchant_id, ref, version, scope, warranty, deposit_kind,
                                   subtotal_cents, tax_cents, total_cents, valid_hours, state)
                            values (?, ?, ?, 'QT-T', ?, 'scope', 'none', 'none', ?, 0, ?, 72, 'draft')
                            """)
                    .params(quote, request, biz.merchantId(), (int) (Math.random() * 1000) + 10, subtotal, subtotal)
                    .update();
        }

        private void insertLine(String quote, int position, long amount) {
            jdbc.sql("""
                            insert into booking.quote_lines (id, quote_id, position, kind, description, qty, unit_cents,
                                   amount_cents, taxable)
                            values (?, ?, ?, 'labour', 'Work', 1, ?, ?, true)
                            """).params(Ids.next(), quote, position, amount, amount).update();
        }
    }
}
