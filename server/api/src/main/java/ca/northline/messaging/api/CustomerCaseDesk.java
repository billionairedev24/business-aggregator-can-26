package ca.northline.messaging.api;

import ca.northline.shared.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Customer cases in Northline's case queue (S-60, "Something's wrong"): a helpdesk case ({@code messaging.tickets},
 * requester {@code customer}, {@code HD-…}) for staff, with its conversation and the customer's photos. It opens a case
 * and records what the customer reported; it never decides or pays anything — the refund cases it points at
 * ({@code payments}) are decided by the business or a Northline agent.
 */
public interface CustomerCaseDesk {

    /**
     * @param topic {@code refund} (S-60) — the help topic staff filter on
     * @param category S-132's triage category when the customer's report was triaged ({@code damaged}, {@code late} …),
     *     else null
     * @param summary a neutral one-line summary for staff (S-132), else null
     * @param refType {@code order | food | booking}
     * @param refLabel "Order NL-48213", "Booking BK-7712"
     * @param body what the customer reported, for staff (English)
     * @param refundIds the {@code RF-…} cases opened with it
     */
    record NewCase(
            String customerId,
            String topic,
            @Nullable String category,
            @Nullable String summary,
            boolean urgent,
            String refType,
            String refId,
            String refLabel,
            String body,
            List<String> refundIds,
            List<String> attachmentIds,
            Locale locale) {

        public NewCase {
            refundIds = List.copyOf(refundIds);
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    record Opened(String id, String code, Instant slaDueAt) {}

    Opened open(NewCase newCase);

    record Attachment(String id, String fileName, String contentType, long byteSize) {}

    /** @param by {@code you | northline} */
    record Note(Instant at, String by, String body, List<Attachment> attachments) {
        public Note {
            attachments = List.copyOf(attachments);
        }
    }

    /** @param state {@code new | in_progress | waiting | resolved} */
    record CaseThread(String id, String code, String state, List<Note> notes) {
        public CaseThread {
            notes = List.copyOf(notes);
        }
    }

    /** The staff case a refund case was opened with; empty for anyone else's. */
    Optional<CaseThread> forRefund(String customerId, String refundId);

    /** "You can add photos or messages to the case any time": 404 for anyone else's case, 409 once resolved. */
    CaseThread addNote(String customerId, String caseId, String body, List<String> attachmentIds);

    /** A photo for a report or a note: JPG, PNG, HEIC or PDF up to 10 MB (the help form's rules). */
    Attachment upload(String customerId, String fileName, String contentType, Bytes bytes);

    record Content(Bytes bytes, String contentType, String fileName) {}

    /** The customer's own upload. */
    Optional<Content> content(String customerId, String attachmentId);
}
