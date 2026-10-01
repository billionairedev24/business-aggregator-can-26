package ca.northline.messaging.application;

import ca.northline.messaging.application.SupportDesk.Macro;
import ca.northline.messaging.application.SupportDesk.Note;
import ca.northline.messaging.application.SupportDesk.RefundRequest;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-83): helpdesk cases for agents, their conversation, refund requests and the support macros. */
public interface SupportDeskStore {

    /** A {@code messaging.tickets} row as the desk needs it. */
    record TicketRow(
            String id,
            int number,
            String requesterType,
            @Nullable String requesterId,
            @Nullable String merchantId,
            String subject,
            String priority,
            String state,
            @Nullable String agentId,
            @Nullable String agentName,
            Instant createdAt,
            @Nullable Instant slaDueAt,
            @Nullable String lang,
            @Nullable Instant escalatedAt,
            @Nullable String refLabel,
            Map<String, Object> context) {
        public TicketRow {
            context = Map.copyOf(context);
        }
    }

    /** Open cases of the businesses in scope (and customers' and couriers' when the scope is everyone), oldest first. */
    List<TicketRow> open(MerchantScope scope, int limit);

    Optional<TicketRow> find(String ticketId);

    /** Locks the case row for an action. */
    Optional<TicketRow> lock(String ticketId);

    /** The desk's figures over cases opened since {@code since}; each null without data. */
    record Figures(
            @Nullable Double medianFirstReplyMinutes,
            @Nullable Double resolvedWithoutEscalation,
            @Nullable Double csat) {}

    Figures figures(MerchantScope scope, Instant since);

    /** The case's conversation thread ({@code messaging.threads} kind {@code case}), creating it when missing. */
    String thread(TicketRow ticket, Instant at);

    List<Note> notes(String ticketId);

    /** @param senderName display snapshot ("Dev K.", "Northline support") */
    void message(String threadId, String senderRole, String senderId, String senderName, String body, Instant at);

    /** After an agent reply: the new state, and the first reply time when this is the first. */
    void replied(String ticketId, String state, Instant at);

    void assign(String ticketId, String agentId, String agentName, Instant at);

    void escalate(String ticketId, String staffId, Instant at);

    void insertRefundRequest(String ticketId, RefundRequest request);

    List<RefundRequest> refundRequests(String ticketId);

    /** The request and its case id. */
    Optional<Map.Entry<String, RefundRequest>> refundRequest(String requestId);

    /** Pending requests with their case id, of cases in scope, oldest first. */
    List<Map.Entry<String, RefundRequest>> pendingRefundRequests(MerchantScope scope, int limit);

    /** False when the request no longer waits. */
    boolean decideRefundRequest(String requestId, String state, String staffId, @Nullable String note, Instant at);

    List<Macro> macros();

    Optional<Macro> macro(String id);

    boolean macroKeyTaken(String key, @Nullable String exceptId);

    void saveMacro(Macro macro, String staffId, Instant at);

    void deleteMacro(String id);
}
