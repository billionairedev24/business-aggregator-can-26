package ca.northline.food.application;

import ca.northline.food.api.KitchenAvailability;
import ca.northline.food.domain.KitchenCalendar;
import ca.northline.food.domain.KitchenTime;
import ca.northline.food.domain.OpeningRanges;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link KitchenAvailability} from the kitchens' settings, hours, holidays, late tickets and live menus. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class KitchenAvailabilityService implements KitchenAvailability {

    private final KitchenCalendarStore store;
    private final Clock clock;

    @Override
    public Map<String, KitchenStatus> now(Collection<String> merchantIds) {
        var out = new HashMap<String, KitchenStatus>();
        merchantIds.forEach(id -> out.put(id, KitchenStatus.CLOSED));
        if (merchantIds.isEmpty()) {
            return out;
        }
        var now = clock.instant();
        for (var row : store.calendars(merchantIds, KitchenTime.today(clock), now)) {
            var state = calendar(row).at(now);
            out.put(
                    row.merchantId(),
                    new KitchenStatus(
                            state.open(),
                            state.opensAt(),
                            state.closesAt(),
                            state.paused(),
                            row.fulfilment(),
                            row.defaultPrepMin() + row.prepBumpMin()));
        }
        return out;
    }

    static KitchenCalendar calendar(KitchenCalendarStore.CalendarRow row) {
        return new KitchenCalendar(
                row.week().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> ranges(e.getValue()))),
                row.holidays().entrySet().stream()
                        .collect(Collectors.<Map.Entry<LocalDate, List<List<String>>>, LocalDate, OpeningRanges>toMap(
                                Map.Entry::getKey, e -> ranges(e.getValue()))),
                row.pausedUntil(),
                row.autoPauseLate(),
                row.lateOrders(),
                row.menuLive());
    }

    /** Stored ranges were checked when saved; anything unreadable counts as closed. */
    private static OpeningRanges ranges(List<List<String>> raw) {
        return OpeningRanges.check("ranges", raw, new ArrayList<RuleViolation.Violation>());
    }
}
