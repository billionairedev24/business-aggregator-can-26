package ca.northline.availability.persistence;

import ca.northline.availability.domain.TimeRange;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** {@code ranges} / {@code special_ranges} jsonb: {@code [["07:00","18:00"],["19:00","21:00"]]} (DATA_MODEL.md). */
@Component
@RequiredArgsConstructor
class RangesJson {

    private final ObjectMapper json;

    String write(List<TimeRange> ranges) {
        return json.writeValueAsString(ranges.stream()
                .map(r -> List.of(r.start().toString(), r.end().toString()))
                .toList());
    }

    List<TimeRange> read(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<TimeRange>();
        for (var pair : json.readTree(raw)) {
            out.add(new TimeRange(
                    LocalTime.parse(pair.get(0).asString()),
                    LocalTime.parse(pair.get(1).asString())));
        }
        return List.copyOf(out);
    }
}
