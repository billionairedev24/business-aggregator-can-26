package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.messaging.api.MessageSent;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Studio › Messages: {@code /threads}, {@code /message-attachments}, the {@code messages} badge. */
@RecordApplicationEvents
class MessagesApiTest extends MessagingApiTest {

    static final String THREADS = "/api/v1/merchants/{m}/threads";

    @Autowired
    ApplicationEvents events;

    /** Owner, technician Jas (assigned to one job), a cook-less provider: 3 customer threads + 1 support thread. */
    record Inbox(
            String merchantId, String owner, String tech, String amara, String tran, String kowalski, String support) {}

    Inbox inbox() {
        var biz = data.business(MerchantRole.OWNER);
        var tech = member(biz.merchantId(), MerchantRole.TECHNICIAN);
        var amara = thread(biz.merchantId(), "customer", biz.userId(), "Amara Osei", "booking", "BK-7712");
        message(amara, "customer", "Hi Ravi — parkade level P2, stall 118.", ago(Duration.ofMinutes(70)));
        message(amara, "merchant", "Perfect, see you at 9.", ago(Duration.ofMinutes(50)));
        message(amara, "customer", "Thanks! The dash light came on again yesterday.", ago(Duration.ofMinutes(1)));
        var tran = thread(biz.merchantId(), "customer", tech, "M. Tran", "booking", "BK-7715");
        message(tran, "customer", "Saturday 10 works. Send the quote?", ago(Duration.ofHours(1)));
        var kowalski = thread(biz.merchantId(), "customer", biz.userId(), "D. Kowalski", "booking", "BK-7690");
        message(kowalski, "customer", "Pads feel great, thanks Ravi.", ago(Duration.ofDays(1)));
        jdbc.sql("update messaging.threads set merchant_read_at = now() where id = ?")
                .params(kowalski)
                .update();
        var support = thread(biz.merchantId(), "support", null, "Northline support", "dispute", "DS-1188");
        message(support, "agent", "Dispute DS-1188: please respond by Thu.", ago(Duration.ofDays(2)));
        return new Inbox(biz.merchantId(), biz.userId(), tech, amara, tran, kowalski, support);
    }

    @Nested
    class Listing {

        @Test
        void ownerSeesEveryCustomerAndSupportThread_newestFirst_withUnreadState() throws Exception {
            var in = inbox();
            mvc.perform(get(THREADS, in.merchantId()).with(TestJwt.member(in.owner())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                            "$.items[*].counterpartName",
                            contains("Amara Osei", "M. Tran", "D. Kowalski", "Northline support")))
                    .andExpect(
                            jsonPath("$.items[0].lastMessage").value("Thanks! The dash light came on again yesterday."))
                    .andExpect(jsonPath("$.items[0].subject").value("brake inspection Tue 9:00"))
                    .andExpect(jsonPath("$.items[0].refCode").value("BK-7712"))
                    .andExpect(jsonPath("$.items[*].unread", contains(true, true, false, true)))
                    .andExpect(jsonPath("$.items[3].kind").value("support"));
            assertThat(badges(in.merchantId(), in.owner(), MerchantRole.OWNER, Locale.CANADA))
                    .containsEntry("messages", "3");
        }

        @Test
        void technicianSeesOnlyThreadsOfTheirOwnJobs() throws Exception {
            var in = inbox();
            mvc.perform(get(THREADS, in.merchantId()).with(TestJwt.member(in.tech())))
                    .andExpect(jsonPath("$.items[*].counterpartName", contains("M. Tran")));
            mvc.perform(get(THREADS + "/{t}", in.merchantId(), in.amara()).with(TestJwt.member(in.tech())))
                    .andExpect(status().isNotFound());
            assertThat(badges(in.merchantId(), in.tech(), MerchantRole.TECHNICIAN, Locale.CANADA))
                    .containsEntry("messages", "1");
        }

        @Test
        void bookkeeperSeesNoCustomerMessages() throws Exception {
            var in = inbox();
            var bookkeeper = member(in.merchantId(), MerchantRole.BOOKKEEPER);
            mvc.perform(get(THREADS, in.merchantId()).with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)));
            assertThat(badges(in.merchantId(), bookkeeper, MerchantRole.BOOKKEEPER, Locale.CANADA))
                    .doesNotContainKey("messages");
        }

        @Test
        void caseThreadsStayOutOfTheInbox() throws Exception {
            var in = inbox();
            thread(in.merchantId(), "case", null, "Northline support", "ticket", "HD-1");
            mvc.perform(get(THREADS, in.merchantId()).with(TestJwt.member(in.owner())))
                    .andExpect(jsonPath("$.items", hasSize(4)));
        }

        @Test
        void nonMemberAndSingleFactorAreForbidden() throws Exception {
            var in = inbox();
            mvc.perform(get(THREADS, in.merchantId()).with(TestJwt.member(data.user("Stranger"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("not_a_member"));
            mvc.perform(get(THREADS, in.merchantId()).with(TestJwt.memberWithoutMfa(in.owner())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }
    }

    @Nested
    class OpenThread {

        @Test
        void returnsMessagesInOrder_andTheProviderQuickReplies_inFrench() throws Exception {
            var in = inbox();
            mvc.perform(get(THREADS + "/{t}", in.merchantId(), in.amara())
                            .header("Accept-Language", "fr-CA")
                            .with(TestJwt.member(in.owner())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.thread.counterpartName").value("Amara Osei"))
                    .andExpect(jsonPath("$.messages[*].senderRole", contains("customer", "merchant", "customer")))
                    .andExpect(jsonPath(
                            "$.quickReplies[*].key",
                            contains(
                                    "provider.on_my_way",
                                    "provider.running_late",
                                    "provider.job_complete",
                                    "provider.extra_parts")))
                    .andExpect(jsonPath("$.quickReplies[0].text").value("Je suis en route"));
        }

        @Test
        void kitchenGetsKitchenQuickReplies() throws Exception {
            var merchant = data.merchant("kitchen", "Pho Dau Bo");
            var owner = member(merchant, MerchantRole.OWNER);
            var t = thread(merchant, "customer", null, "Linh P.", "order", "NL-50102");
            mvc.perform(get(THREADS + "/{t}", merchant, t).with(TestJwt.member(owner)))
                    .andExpect(jsonPath("$.quickReplies[0].text").value("Your order is being prepared"))
                    .andExpect(jsonPath("$.quickReplies", hasSize(4)));
        }

        @Test
        void markingReadClearsUnreadForTheWholeTeam() throws Exception {
            var in = inbox();
            mvc.perform(post(THREADS + "/{t}/read", in.merchantId(), in.amara()).with(TestJwt.member(in.owner())))
                    .andExpect(status().isNoContent());
            mvc.perform(get(THREADS, in.merchantId()).with(TestJwt.member(in.owner())))
                    .andExpect(jsonPath("$.items[0].unread").value(false));
            assertThat(badges(in.merchantId(), in.owner(), MerchantRole.OWNER, Locale.CANADA))
                    .containsEntry("messages", "2");
        }

        @Test
        void otherBusinessesThreadsAreNotFound() throws Exception {
            var in = inbox();
            var other = data.business(MerchantRole.OWNER);
            mvc.perform(get(THREADS + "/{t}", other.merchantId(), in.amara()).with(TestJwt.member(other.userId())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Send {

        @Test
        void ownerSends_messageIsReadAndPublished() throws Exception {
            var in = inbox();
            mvc.perform(postJson(THREADS + "/{t}/messages", """
                                    {"body":"  On my way  ","templateKey":"provider.on_my_way"}""", in.merchantId(), in.amara())
                            .with(TestJwt.member(in.owner())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body").value("On my way"))
                    .andExpect(jsonPath("$.senderRole").value("merchant"))
                    .andExpect(jsonPath("$.templateKey").value("provider.on_my_way"))
                    .andExpect(jsonPath("$.flagged").value(false));
            mvc.perform(get(THREADS, in.merchantId()).with(TestJwt.member(in.owner())))
                    .andExpect(jsonPath("$.items[0].lastMessage").value("On my way"))
                    .andExpect(jsonPath("$.items[0].unread").value(false));
            assertThat(events.stream(MessageSent.class)).singleElement().satisfies(e -> {
                assertThat(e.aggregateId()).isEqualTo(in.amara());
                assertThat(e.merchantId()).isEqualTo(in.merchantId());
                assertThat(e.threadKind()).isEqualTo("customer");
                assertThat(e.flagged()).isFalse();
            });
        }

        @Test
        void unknownQuickReplyKeyIsDropped() throws Exception {
            var in = inbox();
            mvc.perform(postJson(THREADS + "/{t}/messages", """
                                    {"body":"Ready","templateKey":"kitchen.ready"}""", in.merchantId(), in.amara())
                            .with(TestJwt.member(in.owner())))
                    .andExpect(jsonPath("$.templateKey").doesNotExist());
        }

        @Test
        void phoneNumbersAndEmailsAreMasked_andOffPlatformAttemptsAreFlaggedForTrust() throws Exception {
            var in = inbox();
            var result = json(mvc.perform(postJson(THREADS + "/{t}/messages", """
                                    {"body":"Text me at 403-555-0148 or ravi@example.com, e-transfer is fine"}""", in.merchantId(), in.amara())
                            .with(TestJwt.member(in.owner())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body").value("Text me at •••-•••-•••• or •••@•••, e-transfer is fine"))
                    .andExpect(jsonPath("$.flagged").value(true)));
            var messageId = result.get("id").asString();
            eventually(() -> assertThat(jdbc.sql("""
                                    select count(*) from trust.flags
                                     where target_type = 'message' and target_id = ? and rule = 'off_platform_payment'
                                       and state = 'open' and merchant_id = ?
                                    """)
                            .params(messageId, in.merchantId())
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1));
        }

        @Test
        void technicianRepliesInTheirOwnJob_butNotInOthers() throws Exception {
            var in = inbox();
            mvc.perform(postJson(
                                    THREADS + "/{t}/messages",
                                    "{\"body\":\"Running 15 min late\"}",
                                    in.merchantId(),
                                    in.tran())
                            .with(TestJwt.member(in.tech())))
                    .andExpect(status().isCreated());
            mvc.perform(postJson(THREADS + "/{t}/messages", "{\"body\":\"Hi\"}", in.merchantId(), in.amara())
                            .with(TestJwt.member(in.tech())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void bookkeeperMayNotSend() throws Exception {
            var in = inbox();
            var bookkeeper = member(in.merchantId(), MerchantRole.BOOKKEEPER);
            mvc.perform(postJson(THREADS + "/{t}/messages", "{\"body\":\"Hi\"}", in.merchantId(), in.amara())
                            .with(TestJwt.member(bookkeeper)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }

        @Test
        void emptyMessageIs422() throws Exception {
            var in = inbox();
            mvc.perform(postJson(THREADS + "/{t}/messages", "{\"body\":\"   \"}", in.merchantId(), in.amara())
                            .with(TestJwt.member(in.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("body"))
                    .andExpect(jsonPath("$.errors[0].rule").value("required"))
                    .andExpect(jsonPath("$.errors[0].message").value("Write a message or attach a file."));
        }

        @Test
        void tooLongMessageIs422() throws Exception {
            var in = inbox();
            mvc.perform(postJson(
                                    THREADS + "/{t}/messages",
                                    "{\"body\":\"%s\"}".formatted("x".repeat(2001)),
                                    in.merchantId(),
                                    in.amara())
                            .with(TestJwt.member(in.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("body"))
                    .andExpect(jsonPath("$.errors[0].rule").value("length"))
                    .andExpect(jsonPath("$.errors[0].message").value("Keep messages under 2,000 characters."));
        }

        @Test
        void moreThanFiveFilesIs422() throws Exception {
            var in = inbox();
            mvc.perform(postJson(THREADS + "/{t}/messages", """
                                    {"attachmentIds":["a","b","c","d","e","f"]}""", in.merchantId(), in.amara())
                            .with(TestJwt.member(in.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("attachmentIds"))
                    .andExpect(jsonPath("$.errors[0].message").value("Attach up to 5 files."));
        }

        @Test
        void unknownOrForeignAttachmentIs422() throws Exception {
            var in = inbox();
            mvc.perform(postJson(THREADS + "/{t}/messages", """
                                    {"attachmentIds":["01J9ZD3V0000000000000NOPE"]}""", in.merchantId(), in.amara())
                            .with(TestJwt.member(in.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("attachmentIds"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("That file is no longer available. Attach it again."));
        }
    }

    @Nested
    class Attachments {

        static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        static final String UPLOAD = "/api/v1/merchants/{m}/message-attachments";

        @Test
        void uploadThenSendWithTheFile_andDownloadIt() throws Exception {
            var in = inbox();
            var file = new MockMultipartFile("file", "dash-light.png", "image/png", PNG);
            var id = json(mvc.perform(multipart(UPLOAD, in.merchantId())
                                    .file(file)
                                    .with(TestJwt.member(in.owner())))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.fileName").value("dash-light.png"))
                            .andExpect(jsonPath("$.byteSize").value(PNG.length)))
                    .get("id")
                    .asString();
            mvc.perform(postJson(
                                    THREADS + "/{t}/messages",
                                    "{\"attachmentIds\":[\"%s\"]}".formatted(id),
                                    in.merchantId(),
                                    in.amara())
                            .with(TestJwt.member(in.owner())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.body").value(""))
                    .andExpect(jsonPath("$.attachments[0].id").value(id));
            mvc.perform(get(THREADS + "/{t}", in.merchantId(), in.amara()).with(TestJwt.member(in.owner())))
                    .andExpect(
                            jsonPath("$.messages[3].attachments[0].contentType").value("image/png"));
            mvc.perform(get(UPLOAD + "/{id}", in.merchantId(), id).with(TestJwt.member(in.tech())))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(content().bytes(PNG));
            var other = data.business(MerchantRole.OWNER);
            mvc.perform(get(UPLOAD + "/{id}", other.merchantId(), id).with(TestJwt.member(other.userId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void wrongTypeIs422() throws Exception {
            var in = inbox();
            var file =
                    new MockMultipartFile("file", "run.exe", "application/x-msdownload", new byte[] {'M', 'Z', 0, 0});
            mvc.perform(multipart(UPLOAD, in.merchantId()).file(file).with(TestJwt.member(in.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("file"))
                    .andExpect(jsonPath("$.errors[0].message").value("Attach JPG, PNG, HEIC or PDF files."));
        }

        @Test
        void disguisedTypeIs422() throws Exception {
            var in = inbox();
            var file =
                    new MockMultipartFile("file", "x.png", "image/png", new byte[] {'M', 'Z', 0, 0, 0, 0, 0, 0, 0, 0});
            mvc.perform(multipart(UPLOAD, in.merchantId()).file(file).with(TestJwt.member(in.owner())))
                    .andExpect(jsonPath("$.errors[0].message").value("Attach JPG, PNG, HEIC or PDF files."));
        }

        @Test
        void tooLargeIs422() throws Exception {
            var in = inbox();
            var big = new byte[10 * 1024 * 1024 + 1];
            System.arraycopy(PNG, 0, big, 0, PNG.length);
            var file = new MockMultipartFile("file", "huge.png", "image/png", big);
            mvc.perform(multipart(UPLOAD, in.merchantId()).file(file).with(TestJwt.member(in.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Files can be up to 10 MB."));
        }

        @Test
        void missingFileIs422() throws Exception {
            var in = inbox();
            mvc.perform(multipart(UPLOAD, in.merchantId()).with(TestJwt.member(in.owner())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose a file to attach."));
        }
    }
}
