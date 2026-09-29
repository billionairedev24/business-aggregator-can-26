package ca.northline.messaging.application;

import ca.northline.messaging.api.CaseReferences.Reference;
import ca.northline.messaging.application.BrowseInbox.Message;
import ca.northline.messaging.domain.TicketPriority;
import ca.northline.messaging.domain.TicketState;
import ca.northline.shared.security.MerchantRole;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Help › My cases and Contact support: open a case, list and read cases, reply in a case. */
public interface ManageHelpCases {

    record CaseSummary(
            String id,
            String code,
            String subject,
            String topic,
            TicketState state,
            TicketPriority priority,
            boolean urgent,
            @Nullable String channel,
            @Nullable String agentName,
            @Nullable Instant lastAgentReplyAt,
            @Nullable Instant slaDueAt,
            @Nullable Instant resolvedAt,
            @Nullable String resolutionNote,
            @Nullable String refType,
            @Nullable String refId,
            @Nullable String refLabel,
            Instant createdAt) {}

    record CaseDetail(CaseSummary summary, List<Message> messages) {}

    record Open(
            String merchantId,
            String userId,
            MerchantRole role,
            String topic,
            @Nullable String refType,
            @Nullable String refId,
            @Nullable String refLabel,
            String body,
            List<String> attachmentIds,
            String channel,
            boolean urgent,
            Locale locale) {}

    record Reply(
            String merchantId,
            String caseId,
            String userId,
            @Nullable String body,
            List<String> attachmentIds) {}

    List<CaseSummary> cases(String merchantId);

    CaseSummary open(Open command);

    CaseDetail view(String merchantId, String caseId);

    Message reply(Reply command);

    /** Records the case form's "Related to" list offers (every {@code CaseReferences} contribution). */
    List<Reference> related(String merchantId, Locale locale);
}
