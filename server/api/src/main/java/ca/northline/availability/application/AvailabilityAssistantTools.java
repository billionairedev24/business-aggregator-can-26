package ca.northline.availability.application;

import ca.northline.ai.api.AssistantTool;
import ca.northline.availability.application.AvailabilityUseCases.ViewHours;
import ca.northline.availability.application.AvailabilityUseCases.ViewTimeOff;
import ca.northline.shared.security.MerchantPermission;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** S-130: the Studio assistant's availability tool, over the Availability screen's {@link ViewHours} and {@link ViewTimeOff}. */
final class AvailabilityAssistantTools {
    private AvailabilityAssistantTools() {}

    @Component
    @RequiredArgsConstructor
    static final class HoursTool implements AssistantTool {
        private final ViewHours hours;
        private final ViewTimeOff timeOff;

        @Override
        public String name() {
            return "get_availability";
        }

        @Override
        public String description() {
            return "Weekly working hours per bookable team member (local times per weekday), upcoming time off and"
                    + " holiday closures.";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of();
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.VIEW;
        }

        @Override
        public String screen() {
            return "availability";
        }

        @Override
        public Result run(Call call) {
            var members = hours.view(call.merchantId()).members().stream()
                    .map(m -> {
                        var days = new LinkedHashMap<String, Object>();
                        m.hours().days().forEach((day, ranges) -> days.put(
                                day.name().toLowerCase(java.util.Locale.ROOT),
                                ranges.stream().map(r -> r.start() + "–" + r.end()).toList()));
                        return Map.of(
                                "member", m.member().name(),
                                "bookable", m.member().bookable(),
                                "effectiveFrom", String.valueOf(m.effectiveFrom()),
                                "hours", days);
                    })
                    .toList();
            var off = timeOff.view(call.merchantId());
            var entries = off.entries().stream()
                    .map(e -> Map.of(
                            "member", String.valueOf(e.memberName()),
                            "from", e.timeOff().startsOn().toString(),
                            "to", e.timeOff().endsOn().toString(),
                            "kind", e.timeOff().kind()))
                    .toList();
            var holidays = off.holidays().stream()
                    .map(h -> Map.of("date", h.holiday().date().toString(), "open", h.open()))
                    .toList();
            return new Result(
                    Map.of("members", members, "timeOff", entries, "holidays", holidays),
                    "availability → " + members.size() + " members");
        }
    }
}
