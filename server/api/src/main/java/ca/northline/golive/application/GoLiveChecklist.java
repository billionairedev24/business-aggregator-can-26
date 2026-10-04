package ca.northline.golive.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * S-118: a market's go-live checklist — every gate with its status, evidence and owner — and the manual records staff
 * make. Console screen {@code go_live}; {@code make go-live-check} reads the same view.
 */
public interface GoLiveChecklist {

    String GATE = "Choose a gate from the checklist.";
    String STATUS = "Choose pass, fail or not applicable.";
    String EVIDENCE = "Describe the evidence in 1 to 1,000 characters.";
    String EVIDENCE_URL = "Give a link that starts with https:// (500 characters at most), or none.";
    String AUTOMATIC = "The platform checks this gate itself; it can't be recorded by hand.";

    /** Every market with its stage and whether a launch request waits, by province then city. */
    List<MarketSummary> markets();

    Checklist checklist(String marketId);

    /** Records a manual gate (or an automatic one whose source isn't configured). */
    Checklist record(String marketId, String gate, Attestation attestation, Actor actor);

    /** @param role the console role(s) acted with, for the audit log */
    record Actor(String userId, String role) {}

    /**
     * @param status {@code pass | fail | not_applicable}
     * @param source {@code console} (a person) or {@code script} ({@code make go-live-check RECORD=1})
     */
    record Attestation(
            String status, String evidence, @Nullable String evidenceUrl, String source) {}

    record MarketSummary(
            String id,
            String city,
            String province,
            String stage,
            boolean requestPending,
            @Nullable Instant launchedAt) {}

    record Person(String id, String name) {}

    /**
     * @param frenchFirst the market's language rules are French-first (S-116): the French coverage gate applies
     * @param zone the market's time zone (hypercare days are its local dates)
     */
    record Market(String id, String city, String province, String stage, boolean frenchFirst, String zone) {}

    /**
     * One gate.
     *
     * @param kind {@code auto | manual | auto_or_manual}
     * @param status {@code pass | fail | pending | not_applicable}
     * @param code what the evidence says, a code the console words (for example {@code pilot_ready},
     *     {@code oncall_gap} or {@code not_recorded}), with its {@code params}
     * @param evidence what the person or script wrote, for a recorded gate
     * @param recordable whether a record counts for it now (manual, or automatic without its source)
     */
    record GateView(
            String key,
            String kind,
            String owner,
            boolean required,
            String status,
            String code,
            Map<String, String> params,
            @Nullable String evidence,
            @Nullable String evidenceUrl,
            @Nullable String source,
            @Nullable Person recordedBy,
            @Nullable Instant recordedAt,
            boolean recordable,
            String runbook) {

        public GateView {
            params = Map.copyOf(params);
        }
    }

    /** @param blocking required gates in the request that didn't clear when it was asked */
    record RequestView(
            String id,
            String state,
            Person requestedBy,
            Instant requestedAt,
            Instant expiresAt,
            @Nullable String note,
            boolean override,
            @Nullable String overrideReason,
            List<String> blocking,
            @Nullable Person decidedBy,
            @Nullable Instant decidedAt,
            @Nullable String decisionNote) {

        public RequestView {
            blocking = List.copyOf(blocking);
        }
    }

    /** @param kind {@code launched | rolled_back} */
    record EventView(
            String kind,
            Person by,
            Instant at,
            @Nullable String reason,
            @Nullable String requestId) {}

    record HypercareDayView(LocalDate date, Person primary, Person secondary, Person business) {}

    record Hypercare(LocalDate startsOn, LocalDate endsOn, List<HypercareDayView> days) {
        public Hypercare {
            days = List.copyOf(days);
        }
    }

    /**
     * @param blocking required gates that don't clear (pass or not applicable)
     * @param ready nothing blocks
     * @param request the launch request waiting for a second admin
     * @param requests the latest requests, newest first
     * @param events launches and rollbacks, newest first
     */
    record Checklist(
            Market market,
            Instant generatedAt,
            List<GateView> gates,
            List<String> blocking,
            boolean ready,
            @Nullable RequestView request,
            List<RequestView> requests,
            List<EventView> events,
            @Nullable Hypercare hypercare) {

        public Checklist {
            gates = List.copyOf(gates);
            blocking = List.copyOf(blocking);
            requests = List.copyOf(requests);
            events = List.copyOf(events);
        }
    }
}
