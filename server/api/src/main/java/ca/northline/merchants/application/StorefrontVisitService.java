package ca.northline.merchants.application;

import ca.northline.merchants.api.StorefrontVisits;
import ca.northline.region.api.MerchantPlaces;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-75 storefront analytics. A visit is counted when the consumer page reports it (once per tab session, decided in the
 * browser); crawlers and link previews that announce themselves are not counted. The count goes to the business's
 * today, in its own time zone (S-134). Nothing but the number is kept.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class StorefrontVisitService implements StorefrontVisits, CountStorefrontVisit {

    /** User agents of crawlers, link previews and monitors (they don't see a page as a person does). */
    static final Pattern NOT_A_PERSON = Pattern.compile(
            "bot|crawl|spider|slurp|preview|facebookexternalhit|embedly|headless|lighthouse|pingdom|curl|wget|python",
            Pattern.CASE_INSENSITIVE);

    private final StorefrontUseCases.ViewPublishedStorefront published;
    private final StorefrontVisitStore visits;
    private final MerchantPlaces places;
    private final Clock clock;

    @Override
    @Transactional
    public void count(String slug, @Nullable String userAgent) {
        var merchantId = published.bySlug(slug).storefront().getMerchantId(); // 404 unless published and active
        if (userAgent == null
                || userAgent.isBlank()
                || NOT_A_PERSON.matcher(userAgent.toLowerCase(Locale.ROOT)).find()) {
            return;
        }
        visits.increment(
                merchantId, LocalDate.now(clock.withZone(places.of(merchantId).zone())));
    }

    @Override
    public List<DayVisits> daily(String merchantId, LocalDate from, LocalDate to) {
        return visits.daily(merchantId, from, to);
    }
}
