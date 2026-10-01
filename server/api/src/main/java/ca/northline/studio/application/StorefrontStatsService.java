package ca.northline.studio.application;

import ca.northline.booking.api.BookingInsights;
import ca.northline.merchants.api.StorefrontVisits;
import ca.northline.orders.api.OrderInsights;
import ca.northline.region.api.MerchantPlaces;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Composes {@link StorefrontStats} from the merchants, booking and orders modules' public APIs. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class StorefrontStatsService implements StorefrontStats {

    private final StorefrontVisits visits;
    private final BookingInsights bookings;
    private final OrderInsights orders;
    private final MerchantPlaces places;
    private final Clock clock;

    @Override
    public Stats of(String merchantId) {
        var zone = places.of(merchantId).zone();
        var today = LocalDate.now(clock.withZone(zone));
        var from = today.minusDays(DAYS - 1L);
        var to = today.plusDays(1);
        var byDay = new HashMap<LocalDate, Integer>();
        visits.daily(merchantId, from, to).forEach(d -> byDay.put(d.day(), d.visits()));
        var daily = IntStream.range(0, DAYS)
                .mapToObj(i -> from.plusDays(i))
                .map(d -> new Day(d, byDay.getOrDefault(d, 0)))
                .toList();
        long total = daily.stream().mapToLong(Day::visits).sum();
        var start = from.atStartOfDay(zone).toInstant();
        var end = to.atStartOfDay(zone).toInstant();
        long booked = bookings.bookingsMade(merchantId, start, end)
                + orders.volume(merchantId, start, end).orders();
        return new Stats(total, booked, total == 0 ? null : Math.round(booked * 10_000.0 / total), daily);
    }
}
