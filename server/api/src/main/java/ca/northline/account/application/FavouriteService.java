package ca.northline.account.application;

import ca.northline.account.application.Favourites.Favourite;
import ca.northline.account.application.Favourites.FavouriteStore;
import ca.northline.account.application.Favourites.ManageFavourites;
import ca.northline.booking.api.CustomerHistory;
import ca.northline.orders.api.CustomerOrders;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Favourite providers &amp; shops. A card says what the person did with the business ("3 jobs · last Oct 12", from
 * their own bookings and orders) and whether a quote from it is still open ("View quote").
 */
@Service
@RequiredArgsConstructor
@Transactional
class FavouriteService implements ManageFavourites {

    private final FavouriteStore store;
    private final Businesses businesses;
    private final CustomerHistory history;
    private final CustomerOrders orders;
    private final Clock clock;

    private record Visits(int count, @Nullable Instant last) {
        static final Visits NONE = new Visits(0, null);

        Visits plus(Instant at) {
            return new Visits(count + 1, last == null || at.isAfter(last) ? at : last);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Favourite> list(String userId) {
        var stored = store.of(userId);
        if (stored.isEmpty()) {
            return List.of();
        }
        var visits = new HashMap<String, Visits>();
        for (var b : history.bookings(userId, ViewActivity.LIMIT)) {
            if (!"cancelled".equals(b.state())) {
                visits.put(
                        b.merchantId(),
                        visits.getOrDefault(b.merchantId(), Visits.NONE).plus(b.startsAt()));
            }
        }
        for (var o : orders.recent(userId, ViewActivity.LIMIT)) {
            if (!"cancelled".equals(o.state())) {
                o.merchantIds()
                        .forEach(m -> visits.put(
                                m, visits.getOrDefault(m, Visits.NONE).plus(o.placedAt())));
            }
        }
        var openQuotes = new HashMap<String, String>();
        history.openRequests(userId, ViewActivity.LIMIT)
                .forEach(r -> r.openQuotes().forEach(q -> openQuotes.putIfAbsent(q.merchantId(), q.quoteId())));
        var names =
                businesses.of(stored.stream().map(Favourites.Stored::merchantId).toList());
        var out = new ArrayList<Favourite>();
        for (var s : stored) {
            var b = names.get(s.merchantId());
            if (b == null) {
                continue;
            }
            var v = visits.getOrDefault(s.merchantId(), Visits.NONE);
            out.add(new Favourite(
                    b.merchantId(),
                    b.name(),
                    b.type(),
                    b.tier(),
                    b.slug(),
                    b.categoryId(),
                    v.count(),
                    v.last(),
                    openQuotes.get(s.merchantId()),
                    s.addedAt()));
        }
        return out;
    }

    @Override
    public void add(String userId, String merchantId) {
        var business = businesses.one(merchantId).filter(Businesses.Business::active);
        if (business.isEmpty()) {
            throw new NotFound("merchant", merchantId);
        }
        store.add(userId, merchantId, clock.instant());
    }

    @Override
    public void remove(String userId, String merchantId) {
        store.remove(userId, merchantId);
    }
}
