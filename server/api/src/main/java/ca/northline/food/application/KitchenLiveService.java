package ca.northline.food.application;

import ca.northline.food.api.KitchenPaused;
import ca.northline.food.api.KitchenResumed;
import ca.northline.food.application.KitchenTicketStore.LiveOrderRow;
import ca.northline.food.application.KitchenUseCases.Handoff;
import ca.northline.food.application.KitchenUseCases.KitchenLive;
import ca.northline.food.application.KitchenUseCases.LiveBoard;
import ca.northline.food.application.KitchenUseCases.LiveCounts;
import ca.northline.food.application.KitchenUseCases.LiveLine;
import ca.northline.food.application.KitchenUseCases.LiveTicket;
import ca.northline.food.application.KitchenUseCases.PrepShown;
import ca.northline.food.domain.KitchenPause;
import ca.northline.food.domain.KitchenStage;
import ca.northline.food.domain.KitchenTicket;
import ca.northline.food.domain.PrepPolicy;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.identity.api.PersonDirectory.Person;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class KitchenLiveService implements KitchenLive {

    private final KitchenTicketStore tickets;
    private final KitchenSettingsStore settings;
    private final PersonDirectory people;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final KitchenMetrics metrics;

    @Override
    public LiveBoard board(String merchantId) {
        var now = clock.instant();
        var rows = tickets.open(merchantId, now);
        var names = people.people(rows.stream()
                .flatMap(r -> Stream.of(r.customerId(), r.courierUserId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        var items = rows.stream()
                .sorted(Comparator.comparingInt((LiveOrderRow r) -> rank(r.stage()))
                        .thenComparing(LiveOrderRow::placedAt)
                        .thenComparing(LiveOrderRow::orderId))
                .map(r -> ticket(r, names))
                .toList();
        var s = KitchenSettingsService.settingsOf(settings, merchantId);
        return new LiveBoard(
                items,
                new LiveCounts(
                        items.size(),
                        count(items, KitchenStage.NEW),
                        count(items, KitchenStage.COOKING),
                        count(items, KitchenStage.READY)),
                new PrepShown(s.defaultPrepMin(), s.prepBumpMin(), s.defaultPrepMin() + s.prepBumpMin()),
                KitchenPause.paused(s.pausedUntil(), now) ? s.pausedUntil() : null);
    }

    @Override
    @Transactional
    public LiveBoard accept(String merchantId, String orderId, String actorId) {
        var ticket = require(merchantId, orderId);
        var load = tickets.load(merchantId, orderId);
        var s = KitchenSettingsService.settingsOf(settings, merchantId);
        var policy = new PrepPolicy(s.defaultPrepMin(), s.prepBumpMin(), s.largeOrderCents(), s.largeOrderAddMin());
        var event = ticket.accept(actorId, clock.instant(), policy.promiseFor(load.slowestItemAddMin(), load.cents()));
        tickets.save(ticket);
        events.publishEvent(event);
        metrics.accepted(ticket);
        return board(merchantId);
    }

    @Override
    @Transactional
    public LiveBoard ready(String merchantId, String orderId, String actorId) {
        var ticket = require(merchantId, orderId);
        var event = ticket.ready(actorId, clock.instant());
        tickets.save(ticket);
        events.publishEvent(event);
        metrics.ready(ticket);
        return board(merchantId);
    }

    @Override
    @Transactional
    public LiveBoard handOff(String merchantId, String orderId, String actorId) {
        var ticket = require(merchantId, orderId);
        var event = ticket.handOff(actorId, clock.instant());
        tickets.save(ticket);
        events.publishEvent(event);
        metrics.handedOff(ticket);
        return board(merchantId);
    }

    @Override
    @Transactional
    public LiveBoard bumpPrep(String merchantId) {
        var s = KitchenSettingsService.settingsOf(settings, merchantId);
        var bumped =
                new PrepPolicy(s.defaultPrepMin(), s.prepBumpMin(), s.largeOrderCents(), s.largeOrderAddMin()).bumped();
        settings.save(s.toBuilder().prepBumpMin(bumped.bumpMin()).build());
        return board(merchantId);
    }

    @Override
    @Transactional
    public LiveBoard resetPrep(String merchantId) {
        var s = KitchenSettingsService.settingsOf(settings, merchantId);
        settings.save(s.toBuilder().prepBumpMin(0).build());
        return board(merchantId);
    }

    @Override
    @Transactional
    public LiveBoard pause(String merchantId, String actorId) {
        var now = clock.instant();
        var until = KitchenPause.until(now);
        var s = KitchenSettingsService.settingsOf(settings, merchantId);
        settings.save(s.toBuilder().pausedUntil(until).pausedBy(actorId).build());
        events.publishEvent(new KitchenPaused(Ids.next(), now, merchantId, actorId, until));
        return board(merchantId);
    }

    @Override
    @Transactional
    public LiveBoard resume(String merchantId, String actorId) {
        var now = clock.instant();
        var s = KitchenSettingsService.settingsOf(settings, merchantId);
        if (KitchenPause.paused(s.pausedUntil(), now)) {
            settings.save(s.toBuilder().pausedUntil(null).pausedBy(null).build());
            events.publishEvent(new KitchenResumed(Ids.next(), now, merchantId, actorId));
        }
        return board(merchantId);
    }

    private KitchenTicket require(String merchantId, String orderId) {
        return tickets.ticket(merchantId, orderId).orElseThrow(() -> new NotFound("order", orderId));
    }

    private static int count(List<LiveTicket> items, KitchenStage stage) {
        return (int) items.stream().filter(t -> t.stage() == stage).count();
    }

    /** As the design lays the board out: New, then Cooking, then Ready; oldest first within a stage. */
    private static int rank(KitchenStage stage) {
        return switch (stage) {
            case NEW -> 0;
            case COOKING -> 1;
            case READY -> 2;
            case HANDED_OFF -> 3;
        };
    }

    private static LiveTicket ticket(LiveOrderRow r, Map<String, Person> names) {
        var customer = r.customerId() == null ? null : names.get(r.customerId());
        var customerName = customer == null ? null : r.groupSize() > 1 ? customer.firstName() : customer.shortName();
        return new LiveTicket(
                r.orderId(),
                r.ref(),
                customerName,
                r.groupSize(),
                r.placedAt(),
                r.scheduledFor(),
                r.stage(),
                r.lines().stream()
                        .map(l -> new LiveLine(l.qty(), l.title(), l.modifiers()))
                        .toList(),
                r.fulfilmentMode(),
                handoff(r, names),
                r.readyBy());
    }

    private static Handoff handoff(LiveOrderRow r, Map<String, Person> names) {
        if ("pickup".equals(r.fulfilmentMode())) {
            return r.customerEta() == null
                    ? new Handoff("customer", "none", null, null)
                    : new Handoff("customer", "arriving", null, r.customerEta());
        }
        if (!r.courierAssigned()) {
            return new Handoff("courier", "finding", null, null);
        }
        var courier = r.courierUserId() == null ? null : names.get(r.courierUserId());
        var name = courier == null ? null : courier.firstName();
        if (r.courierArrivedAt() != null) {
            return new Handoff("courier", "waiting", name, null);
        }
        if (r.courierEta() != null) {
            return new Handoff("courier", "arriving", name, r.courierEta());
        }
        return new Handoff("courier", "assigned", name, null);
    }
}
