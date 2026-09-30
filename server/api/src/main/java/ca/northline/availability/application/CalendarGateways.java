package ca.northline.availability.application;

import ca.northline.availability.domain.CalendarProvider;
import ca.northline.shared.Conflict;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The {@link CalendarGateway} per two-way provider. */
@Component
class CalendarGateways {

    private final Map<CalendarProvider, CalendarGateway> byProvider = new EnumMap<>(CalendarProvider.class);

    CalendarGateways(List<CalendarGateway> gateways) {
        gateways.forEach(g -> byProvider.put(g.provider(), g));
    }

    CalendarGateway get(CalendarProvider provider) {
        var gateway = byProvider.get(provider);
        if (gateway == null) {
            throw new Conflict("calendar_provider_unavailable", "This calendar can't be connected yet.");
        }
        return gateway;
    }

    boolean available(CalendarProvider provider) {
        var gateway = byProvider.get(provider);
        return gateway != null && gateway.available();
    }
}
