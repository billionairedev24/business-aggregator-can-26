package ca.northline.food.application;

import ca.northline.food.api.FoodCheckoutFacts;
import ca.northline.food.api.KitchenAvailability;
import ca.northline.food.api.KitchenAvailability.KitchenStatus;
import ca.northline.food.application.ComboStore.ComboRow;
import ca.northline.food.application.KitchenCalendarStore.CalendarRow;
import ca.northline.food.application.PublicKitchenViews.Card;
import ca.northline.food.application.PublicKitchenViews.Combo;
import ca.northline.food.application.PublicKitchenViews.Dish;
import ca.northline.food.application.PublicKitchenViews.Group;
import ca.northline.food.application.PublicKitchenViews.Kitchens;
import ca.northline.food.application.PublicKitchenViews.Option;
import ca.northline.food.application.PublicKitchenViews.Restaurant;
import ca.northline.food.application.PublicKitchenViews.Section;
import ca.northline.food.application.PublicKitchenViews.Slot;
import ca.northline.food.domain.ComboPrice;
import ca.northline.food.domain.FoodFees;
import ca.northline.merchants.api.PublicDirectory;
import ca.northline.merchants.api.PublicDirectory.PublicBusiness;
import ca.northline.region.api.Markets;
import ca.northline.region.api.TaxRates;
import ca.northline.shared.NotFound;
import ca.northline.trust.api.RatingQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consumer site's food reads (S-57): kitchens of a city with open state, times, distance and fees (landing),
 * one kitchen's orderable menu (restaurant page), and the same facts for checkout ({@link FoodCheckoutFacts}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class PublicKitchenService implements PublicKitchenUseCases, FoodCheckoutFacts {

    /** A scheduled order is placed at least this long before its window (prep + a margin). */
    static final Duration SCHEDULE_LEAD = Duration.ofMinutes(45);
    /** Scheduled windows offered: today and tomorrow (design: Today / Tomorrow). */
    static final int SCHEDULE_DAYS = 2;

    private final PublicDirectory directory;
    private final KitchenAvailability availability;
    private final KitchenCalendarStore calendars;
    private final RatingQuery ratings;
    private final KitchenMerchantFacts facts;
    private final OrderableMenu menus;
    private final Clock clock;
    private final Markets markets;
    private final TaxRates taxes;

    @Override
    public Kitchens kitchens(String city, @Nullable Double lat, @Nullable Double lng) {
        var kitchens = directory.active(Set.of("kitchen"), city);
        var ids = kitchens.stream().map(PublicBusiness::merchantId).toList();
        var status = availability.now(
                kitchens.stream().map(PublicKitchenService::ref).toList());
        var rows = calendarsOf(ids);
        var cards = kitchens.stream()
                .flatMap(k -> Optional.ofNullable(rows.get(k.merchantId()))
                        .map(row -> card(k, status.getOrDefault(k.merchantId(), KitchenStatus.CLOSED), row, lat, lng))
                        .stream())
                .sorted(Comparator.comparingInt((Card c) -> c.open() ? 0 : 1)
                        .thenComparing(c -> c.distanceKm() == null ? Double.MAX_VALUE : c.distanceKm())
                        .thenComparing(Card::name))
                .toList();
        return new Kitchens(city, cards);
    }

    @Override
    public Restaurant restaurant(String slug, @Nullable Double lat, @Nullable Double lng) {
        var business = directory
                .bySlug(slug)
                .filter(b -> b.type().equals("kitchen"))
                .orElseThrow(() -> new NotFound("kitchen", slug));
        var row = calendarsOf(List.of(business.merchantId())).get(business.merchantId());
        if (row == null) {
            throw new NotFound("kitchen", slug);
        }
        var status = availability.now(List.of(ref(business))).getOrDefault(business.merchantId(), KitchenStatus.CLOSED);
        var card = card(business, status, row, lat, lng);
        var snapshot = menus.load(business.merchantId());
        var zone = markets.zone(business.province());
        var now = clock.instant().atZone(zone);
        var today = now.toLocalDate();
        var sections = snapshot.sections().stream()
                .map(s -> new Section(
                        s.id(),
                        s.name(),
                        Objects.requireNonNull(snapshot.menus().get(s.menuId())).name(),
                        snapshot.itemsOf(s.id()).stream()
                                .map(i -> dish(snapshot, i, now, today))
                                .toList()))
                .filter(s -> !s.items().isEmpty())
                .toList();
        var combos = snapshot.combos().stream()
                .map(c -> combo(snapshot, c, now, today))
                .flatMap(Optional::stream)
                .toList();
        var permit = facts.foodSafety(business.merchantId()).permit();
        return new Restaurant(
                card,
                business.address(),
                business.province(),
                "verified".equals(permit.status()),
                FoodFees.MIN_ORDER_CENTS,
                FoodFees.SERVICE_FEE_BPS,
                taxBps(business.province()),
                slots(row, card, zone),
                sections,
                combos);
    }

    @Override
    public Optional<Kitchen> kitchen(String merchantId, @Nullable Double lat, @Nullable Double lng) {
        var business = directory.byId(merchantId).filter(b -> b.type().equals("kitchen"));
        if (business.isEmpty()) {
            return Optional.empty();
        }
        var row = calendarsOf(List.of(merchantId)).get(merchantId);
        if (row == null) {
            return Optional.empty();
        }
        var status = availability.now(List.of(ref(business.get()))).getOrDefault(merchantId, KitchenStatus.CLOSED);
        var c = card(business.get(), status, row, lat, lng);
        return Optional.of(new Kitchen(
                merchantId,
                c.name(),
                c.slug(),
                business.get().province(),
                c.open(),
                c.paused(),
                c.fulfilment(),
                c.delivers(),
                c.distanceKm(),
                c.deliveryFeeCents(),
                c.prepMin(),
                c.etaFromMin(),
                c.etaToMin(),
                c.pickupFromMin(),
                c.pickupToMin(),
                FoodFees.MIN_ORDER_CENTS,
                FoodFees.SERVICE_FEE_BPS,
                slots(row, c, markets.zone(business.get().province()))));
    }

    private Map<String, CalendarRow> calendarsOf(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        // holidays from yesterday (UTC) cover "today" in every Canadian zone
        var from = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1);
        return calendars.calendars(ids, from, clock.instant()).stream()
                .collect(Collectors.toMap(CalendarRow::merchantId, Function.identity()));
    }

    private int taxBps(@Nullable String province) {
        var code = province != null ? province : markets.defaultProvince();
        return code == null ? 0 : taxes.bpsFor(code);
    }

    private static KitchenAvailability.Kitchen ref(PublicBusiness b) {
        return new KitchenAvailability.Kitchen(b.merchantId(), b.province());
    }

    private List<Instant> slots(CalendarRow row, Card card, ZoneId zone) {
        if (!card.fulfilment().contains("scheduled")) {
            return List.of();
        }
        return KitchenAvailabilityService.calendar(row, zone)
                .slots(
                        clock.instant(),
                        SCHEDULE_LEAD.plusMinutes(card.prepMin()),
                        Math.min(SCHEDULE_DAYS, row.scheduledDays()));
    }

    private Card card(PublicBusiness k, KitchenStatus s, CalendarRow row, @Nullable Double lat, @Nullable Double lng) {
        Double km = lat == null || lng == null || k.lat() == null || k.lng() == null
                ? null
                : FoodFees.km(lat, lng, k.lat(), k.lng());
        var radius = row.radiusKm() != null
                ? row.radiusKm()
                : k.serviceRadiusKm() != null ? k.serviceRadiusKm() : FoodFees.DEFAULT_RADIUS_KM;
        Boolean delivers = km == null ? null : s.fulfilment().contains("courier") && km <= radius;
        var ride = km == null ? 10 : FoodFees.rideMinutes(km);
        var prep = s.prepMin() > 0 ? s.prepMin() : row.defaultPrepMin() + row.prepBumpMin();
        var rating = ratings.summary(k.merchantId());
        return new Card(
                k.merchantId(),
                k.slug(),
                k.displayName(),
                k.cuisines(),
                k.dietary(),
                FoodFees.priceLevel(row.avgItemCents()),
                s.open(),
                s.opensAt(),
                s.closesAt(),
                s.paused(),
                row.fulfilment(),
                prep,
                prep + ride,
                prep + ride + 10,
                Math.max(5, prep - 10),
                Math.max(10, prep - 5),
                km,
                delivers,
                km == null ? FoodFees.UNKNOWN_DISTANCE_FEE_CENTS : FoodFees.deliveryFeeCents(km),
                rating.average(),
                rating.count(),
                k.brandColor());
    }

    private static Dish dish(
            OrderableMenu.Snapshot snapshot, MenuStore.ItemRow i, ZonedDateTime now, java.time.LocalDate today) {
        return new Dish(
                i.id(),
                i.name(),
                i.description(),
                i.priceCents(),
                i.dietary(),
                i.allergens() == null ? List.of() : i.allergens(),
                OrderableMenu.Snapshot.soldOut(i, today),
                snapshot.orderable(i, now, today),
                i.availability().code(),
                snapshot.groupsOf(i).stream()
                        .map(g -> new Group(
                                Objects.requireNonNull(g.id()),
                                g.name(),
                                g.rule().rule().code(),
                                g.rule().count(),
                                g.rule().required(),
                                g.showForOptionIds(),
                                g.options().stream()
                                        .map(o -> new Option(
                                                Objects.requireNonNull(o.id()),
                                                o.name(),
                                                o.priceDeltaCents(),
                                                o.isDefault(),
                                                o.soldOut()))
                                        .toList()))
                        .toList());
    }

    /** A combo whose every slot has an orderable dish; priced from the cheapest dish per slot. */
    private static Optional<Combo> combo(
            OrderableMenu.Snapshot snapshot, ComboRow c, ZonedDateTime now, java.time.LocalDate today) {
        long reference = 0;
        var slots = new java.util.ArrayList<Slot>();
        for (var slot : c.slots()) {
            var eligible = snapshot.eligible(slot).stream()
                    .filter(i -> !OrderableMenu.Snapshot.soldOut(i, today))
                    .toList();
            if (eligible.isEmpty()) {
                return Optional.empty();
            }
            reference += slot.qty()
                    * eligible.stream()
                            .mapToLong(MenuStore.ItemRow::priceCents)
                            .min()
                            .orElse(0);
            slots.add(new Slot(
                    slot.label(),
                    slot.qty(),
                    eligible.stream().map(MenuStore.ItemRow::id).toList()));
        }
        var price = ComboPrice.of(
                reference,
                c.pricing(),
                Objects.requireNonNullElse(c.priceCents(), 0L),
                Objects.requireNonNullElse(c.discountBps(), 0));
        return Optional.of(new Combo(
                c.id(),
                c.name(),
                c.pricing().code(),
                c.priceCents(),
                c.discountBps(),
                price.priceCents(),
                price.savingCents(),
                OrderableMenu.Snapshot.comboOpen(c, now),
                slots));
    }
}
