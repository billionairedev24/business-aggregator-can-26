package ca.northline.messaging.application;

import ca.northline.messaging.application.ManageHelpCases.CaseSummary;
import ca.northline.messaging.domain.SupportCase;
import ca.northline.messaging.domain.TicketPriority;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: helpdesk cases of a business ({@code messaging.tickets}). */
public interface HelpCaseStore {

    /** Account context attached to a case so the business never has to explain it. */
    record Context(String portal, @Nullable String tier, String role, List<RecentEvent> recentEvents) {}

    record RecentEvent(String type, Instant at) {}

    record NewCase(
            String id,
            String merchantId,
            String openedBy,
            String topic,
            String subject,
            TicketPriority priority,
            boolean urgent,
            String channel,
            Instant slaDueAt,
            @Nullable String refType,
            @Nullable String refId,
            @Nullable String refLabel,
            String lang,
            Context context,
            Instant createdAt) {}

    /** Inserts the case and returns its number (HD-number). */
    int insert(NewCase newCase);

    List<CaseSummary> list(String merchantId);

    Optional<CaseSummary> find(String merchantId, String caseId);

    Optional<SupportCase> state(String merchantId, String caseId);

    void update(SupportCase progress, Instant at);

    int openCount(String merchantId);

    /** The last {@code limit} domain events about the business (types and times), newest first. */
    List<RecentEvent> recentEvents(String merchantId, int limit);
}
