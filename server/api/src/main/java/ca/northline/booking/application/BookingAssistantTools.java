package ca.northline.booking.application;

import ca.northline.ai.api.AssistantTool;
import ca.northline.booking.application.JobViews.JobSummary;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.MerchantPermission;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S-130: the Studio assistant's appointment tools, over the Appointments screen's use cases ({@link ListJobs},
 * {@link AdvanceJob}). Technicians see only their own jobs, exactly as on the screen. Jobs carry refs, services,
 * local times, states, the assigned member's name, area and price — never the customer's name or address.
 */
final class BookingAssistantTools {
    private BookingAssistantTools() {}

    static CurrentMember member(AssistantTool.Call call) {
        return new CurrentMember(call.merchantId(), call.userId(), call.role());
    }

    static Map<String, Object> compact(JobSummary j, AssistantTool.Call call) {
        var m = new LinkedHashMap<String, Object>();
        m.put("ref", j.ref() == null ? j.id() : j.ref());
        m.put("service", j.title());
        m.put("startsAt", j.startsAt().atZone(call.zone()).toOffsetDateTime().toString());
        m.put("endsAt", j.endsAt().atZone(call.zone()).toOffsetDateTime().toString());
        m.put("state", j.state());
        m.put("assignedTo", j.memberName());
        m.put("area", j.area());
        m.put("priceCents", j.priceCents());
        return m;
    }

    @Component
    @RequiredArgsConstructor
    static final class ListJobsTool implements AssistantTool {
        private final ListJobs jobs;
        private final Clock clock;

        @Override
        public String name() {
            return "list_jobs";
        }

        @Override
        public String description() {
            return "Booked jobs (appointments) from a date for a number of days, earliest first: ref, service, local"
                    + " start/end time, state (confirmed, en_route, on_site, completed…), who is assigned, area, price.";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of(
                    "from", Map.of("type", "string", "description", "First day, YYYY-MM-DD (default today)"),
                    "days", Map.of("type", "integer", "minimum", 1, "maximum", 31, "description", "Days (default 7)"));
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.VIEW;
        }

        @Override
        public String screen() {
            return "appointments";
        }

        @Override
        public Result run(Call call) {
            LocalDate from;
            try {
                var given = call.text("from");
                from = given == null ? LocalDate.now(clock.withZone(call.zone())) : LocalDate.parse(given);
            } catch (DateTimeParseException _) {
                throw RuleViolation.of("from", "format", "Use a date like 2026-10-02.");
            }
            var days = call.integer("days", 7, 1, 31);
            var start = from.atStartOfDay(call.zone()).toInstant();
            var rows = jobs.list(new ListJobs.Query(member(call), start, start.plus(Duration.ofDays(days)))).stream()
                    .limit(60)
                    .map(j -> compact(j, call))
                    .toList();
            return new Result(
                    Map.of("from", from.toString(), "days", days, "jobs", rows),
                    "jobs from " + from + " (" + days + " d) → " + rows.size());
        }
    }

    /** "Start travel" on a job — a write, run only after the person confirms. */
    @Component
    @RequiredArgsConstructor
    static final class StartTravelTool implements AssistantTool {
        private final ListJobs jobs;
        private final AdvanceJob advance;
        private final Clock clock;

        @Override
        public String name() {
            return "start_travel";
        }

        @Override
        public String description() {
            return "Propose starting travel to a confirmed job today (the customer is told you're on the way)."
                    + " The person must confirm it.";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("ref", Map.of("type", "string", "description", "The job ref, e.g. BK-7712"));
        }

        @Override
        public List<String> required() {
            return List.of("ref");
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.OPERATE;
        }

        @Override
        public String screen() {
            return "appointments";
        }

        @Override
        public boolean write() {
            return true;
        }

        @Override
        public String preview(Call call) {
            var ref = String.valueOf(call.text("ref"));
            return call.locale().getLanguage().equals("fr")
                    ? "Indiquer au client que vous êtes en route pour " + ref
                    : "Tell the customer you're on the way to " + ref;
        }

        @Override
        public Result run(Call call) {
            var ref = String.valueOf(call.text("ref")).strip();
            var today = LocalDate.now(clock.withZone(call.zone())).atStartOfDay(call.zone()).toInstant();
            var job = jobs.list(new ListJobs.Query(member(call), today, today.plus(Duration.ofDays(1)))).stream()
                    .filter(j -> ref.equalsIgnoreCase(j.ref()) || ref.equals(j.id()))
                    .findFirst()
                    .orElseThrow(() -> new NotFound("job", ref));
            var detail = advance.advance(
                    new AdvanceJob.Command(member(call), job.id(), AdvanceJob.Step.START_TRAVEL, null, List.of(), null));
            return new Result(Map.of("ref", ref, "state", detail.state()), "on the way to " + ref);
        }
    }
}
