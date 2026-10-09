package ca.northline.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ca.northline.payments.application.PayoutRepository.ScheduledRunFacts;
import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.MerchantPlaces.MerchantPlace;
import ca.northline.region.api.Regions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Engineering follow-ups (S-119 F6): the minutely scheduled payout run read each business's zone, schedule and
 * today's payouts one by one (≈ 3 queries × businesses, every minute). It now reads the facts of every business in one
 * query and their zones in one more, whatever the number of businesses; only a business that is due is read on its
 * own (to pay it out).
 */
class ScheduledPayoutRunTest {

    static final ZoneId ZONE = ZoneOffset.ofHours(-6);
    // a Friday (the default weekly payout day), after 9:00 in the businesses' zone
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);

    final PayoutRepository payouts = mock(PayoutRepository.class);
    final MerchantPlaces places = mock(MerchantPlaces.class);

    PayoutService service(LocalTime at) {
        var clock = Clock.fixed(FRIDAY.atTime(at).atZone(ZONE).toInstant(), ZoneOffset.UTC);
        return new PayoutService(
                payouts,
                mock(LedgerRepository.class),
                mock(MerchantBalances.class),
                mock(PayoutGateway.class),
                mock(PaymentGateway.class),
                mock(ApplicationEventPublisher.class),
                clock,
                new BusinessTime(places, mock(Regions.class)),
                mock(PaymentMetrics.class));
    }

    void businesses(List<ScheduledRunFacts> facts) {
        when(payouts.scheduledRunFacts()).thenReturn(facts);
        when(places.ofAll(anyCollection())).thenAnswer(call -> {
            Collection<String> ids = call.getArgument(0);
            var map = new LinkedHashMap<String, MerchantPlace>();
            ids.forEach(id -> map.put(id, place()));
            return map;
        });
    }

    static MerchantPlace place() {
        return new MerchantPlace("XX", true, null, null, ZONE, "", "", null);
    }

    static ScheduledRunFacts weekly(String id) {
        return new ScheduledRunFacts(id, PayoutSchedule.DEFAULT, null, false);
    }

    @Test
    void beforeNine_aThousandBusinessesCostTwoReads() {
        businesses(IntStream.range(0, 1_000).mapToObj(i -> weekly("m" + i)).toList());

        assertThat(service(LocalTime.of(8, 59)).runScheduled()).isZero();

        verify(payouts, times(1)).scheduledRunFacts();
        verify(places, times(1)).ofAll(anyCollection());
        verify(places, never()).of(anyString());
        verifyNoMoreInteractions(payouts);
    }

    @Test
    void onThePayoutDay_onlyTheDueBusinessIsReadOnItsOwn() {
        var today = FRIDAY.atTime(9, 0).atZone(ZONE).toInstant();
        var yesterday = today.minusSeconds(86_400);
        businesses(List.of(
                new ScheduledRunFacts("paid-today", PayoutSchedule.DEFAULT, today, false),
                new ScheduledRunFacts("bank-hold", PayoutSchedule.DEFAULT, null, true),
                new ScheduledRunFacts(
                        "monthly",
                        new PayoutSchedule(
                                PayoutSchedule.Frequency.MONTHLY,
                                null,
                                PayoutSchedule.MonthlyAnchor.FIRST,
                                PayoutSchedule.Reserve.NONE),
                        null,
                        false),
                new ScheduledRunFacts("due", PayoutSchedule.DEFAULT, yesterday, false)));
        when(payouts.connectedAccount(anyString())).thenReturn(Optional.empty());

        assertThat(service(LocalTime.of(10, 0)).runScheduled()).isZero(); // "due" has no connected account here

        verify(payouts, times(1)).scheduledRunFacts();
        verify(payouts, times(1)).connectedAccount("due");
        verify(payouts, never()).connectedAccount("paid-today");
        verify(payouts, never()).connectedAccount("bank-hold");
        verify(payouts, never()).connectedAccount("monthly");
        verify(payouts, never()).schedule(any());
        verify(payouts, never()).pendingAccount(any());
        verify(places, never()).of(anyString());
    }

    @Test
    void everyBusinessGetsItsOwnZone() {
        var east = ZoneOffset.ofHours(-3);
        when(payouts.scheduledRunFacts()).thenReturn(List.of(weekly("west"), weekly("east")));
        when(places.ofAll(anyCollection()))
                .thenReturn(
                        Map.of("west", place(), "east", new MerchantPlace("YY", true, null, null, east, "", "", null)));
        when(payouts.connectedAccount(anyString())).thenReturn(Optional.empty());

        // 8:00 in the west is 11:00 in the east: only the eastern business has reached 9:00
        service(LocalTime.of(8, 0)).runScheduled();

        verify(payouts, times(1)).connectedAccount("east");
        verify(payouts, never()).connectedAccount("west");
    }
}
