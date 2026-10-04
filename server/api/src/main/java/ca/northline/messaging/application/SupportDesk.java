package ca.northline.messaging.application;

import ca.northline.shared.Bytes;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Platform console — the support desk (S-83, design 03 {@code support}): the agents' queue over every helpdesk case
 * (businesses' Help › Contact support, S-60 customer cases), the requester's context, the EN/FR macros support leads
 * keep, and the role-gated case actions (reply, take, escalate to trust &amp; safety, refund request to finance). Every
 * action is audit-logged and visible to the requester as a case update where it concerns them.
 */
public interface SupportDesk {

    String BODY_REQUIRED = "Write a reply.";
    String BODY_TOO_LONG = "Keep your reply under 5,000 characters.";
    String AMOUNT_RANGE = "Enter an amount more than $0.";
    String MACRO_KEY = "Use lower-case letters, digits, dots and dashes for the key.";
    String MACRO_TITLE = "Give the macro a title in English and French.";
    String MACRO_BODY = "Write the macro in English and French.";
    String MACRO_TAKEN = "That key is already used.";
    String REQUEST_SELF = "Another person must decide this refund request.";
    String REQUEST_CLOSED = "This refund request was already decided.";
    String ALREADY_ESCALATED = "This case is already with trust & safety.";
    String CASE_RESOLVED = "This case is resolved.";
    String DECISION_REQUIRED = "Choose approve or decline.";
    int BODY_MAX = 5000;

    /** The queue's chips (design "All · 23", "Urgent · 3", "Unassigned · 6", "Mine · 5", "SLA at risk · 2", portals). */
    enum Filter implements CodedEnum {
        ALL,
        URGENT,
        UNASSIGNED,
        MINE,
        SLA_RISK,
        PROVIDERS,
        SELLERS,
        KITCHENS,
        CUSTOMERS
    }

    Queue queue(MerchantScope scope, Filter filter, String staffId);

    TicketDetail ticket(String ticketId);

    /**
     * A file attached to a message of the case's conversation (a customer's problem-report photos, a business's file):
     * empty when the file isn't on this case (or is gone — retention, erasure).
     */
    Optional<File> attachment(String ticketId, String attachmentId);

    record File(Bytes bytes, String contentType, String fileName) {}

    TicketDetail reply(Reply command);

    TicketDetail take(String ticketId, String staffId, String role);

    TicketDetail escalate(String ticketId, @Nullable String note, String staffId, String role);

    TicketDetail requestRefund(String ticketId, long amountCents, @Nullable String note, String staffId, String role);

    TicketDetail decideRefund(String requestId, boolean approve, @Nullable String note, String staffId, String role);

    /** Refund requests waiting for finance, of the businesses in scope (customers' cases when everyone), oldest first. */
    List<PendingRefund> pendingRefunds(MerchantScope scope);

    List<Macro> macros();

    Macro saveMacro(@Nullable String id, MacroInput input, String staffId, String role);

    void deleteMacro(String id, String staffId, String role);

    /** @param resolve "Send &amp; resolve" (else "Send &amp; keep open": waiting on the requester) */
    record Reply(
            String ticketId,
            String body,
            boolean resolve,
            @Nullable String macroKey,
            String staffId,
            String role) {}

    record MacroInput(String key, Map<String, String> title, Map<String, String> body) {
        public MacroInput {
            title = Map.copyOf(title);
            body = Map.copyOf(body);
        }
    }

    record Macro(
            String id,
            String key,
            Map<String, String> title,
            Map<String, String> body,
            @Nullable Instant updatedAt) {
        public Macro {
            title = Map.copyOf(title);
            body = Map.copyOf(body);
        }
    }

    /**
     * Design KPIs and chip counts.
     *
     * @param medianFirstReplyMinutes over the last 30 days; null without replies
     * @param resolvedWithoutEscalation share of cases resolved in the last 30 days that were never escalated
     * @param csat average satisfaction (1–5) of the last 30 days; null without answers
     * @param frenchShare share of open cases in French
     */
    record Kpis(
            long open,
            long urgent,
            @Nullable Double medianFirstReplyMinutes,
            long slaAtRisk,
            @Nullable Double resolvedWithoutEscalation,
            @Nullable Double csat,
            @Nullable Double frenchShare) {}

    record Queue(Kpis kpis, Map<String, Long> counts, List<Ticket> items) {
        public Queue {
            counts = Map.copyOf(counts);
            items = List.copyOf(items);
        }
    }

    /**
     * One case.
     *
     * @param requesterType {@code provider | seller | kitchen | both | customer | courier}
     * @param priority {@code urgent | priority | normal}
     * @param state {@code new | in_progress | waiting | resolved}
     */
    record Ticket(
            String id,
            String code,
            String requesterType,
            String requesterName,
            @Nullable String merchantId,
            String subject,
            String priority,
            String state,
            @Nullable String agentId,
            @Nullable String agentName,
            Instant createdAt,
            @Nullable Instant slaDueAt,
            @Nullable String lang,
            boolean escalated) {}

    /**
     * @param context what the case was opened with (portal, tier, role, recent events; a customer case's refunds and
     *     triage)
     * @param refLabel "Order NL-48102", "Booking BK-7712"
     */
    record TicketDetail(
            Ticket ticket,
            Map<String, Object> context,
            @Nullable String refLabel,
            List<Note> notes,
            List<RefundRequest> refundRequests) {
        public TicketDetail {
            context = Map.copyOf(context);
            notes = List.copyOf(notes);
            refundRequests = List.copyOf(refundRequests);
        }
    }

    /**
     * A message of the case's conversation.
     *
     * @param by {@code merchant | customer | agent | system}
     * @param name display snapshot ("Dev K.")
     */
    record Note(String by, @Nullable String name, String body, Instant at, List<NoteFile> attachments) {
        public Note {
            attachments = List.copyOf(attachments);
        }
    }

    /** A file on a message (photos from the app or the web), opened through the ticket's attachment endpoint. */
    record NoteFile(String id, String fileName, String contentType, long size) {}

    record PendingRefund(RefundRequest request, Ticket ticket) {}

    /** @param requestedByName the asking agent's short name ("Dev K."), filled in for display */
    record RefundRequest(
            String id,
            long amountCents,
            @Nullable String note,
            String requestedBy,
            Instant requestedAt,
            String state,
            @Nullable String decidedBy,
            @Nullable Instant decidedAt,
            @Nullable String decisionNote,
            @Nullable String requestedByName) {

        RefundRequest named(String name) {
            return new RefundRequest(
                    id, amountCents, note, requestedBy, requestedAt, state, decidedBy, decidedAt, decisionNote, name);
        }
    }
}
