package ca.northline.hire;

import ca.northline.shared.Ids;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL-level fixtures for the Services journey: a service business with a published page, a bookable member working
 * every day, services and service-area zones. Plain record over the autowired {@link JdbcClient} (no extra context).
 */
public record HireFixtures(JdbcClient jdbc) {

    /** @param owner the bookable member (owner) */
    public record Provider(String merchantId, String slug, String owner) {}

    /**
     * An active, published provider in Calgary, open 00:00–23:30 every day (so slots exist whenever the test runs),
     * 30-minute interval, no buffer, 60 minutes' notice, 14-day horizon.
     */
    public Provider provider(String name, String tier, List<String> zones) {
        var merchantId = Ids.next();
        var owner = Ids.next();
        var slug = "p-" + merchantId.toLowerCase(java.util.Locale.ROOT).substring(14);
        jdbc.sql("insert into identity.users (id, display_name, locale, status) values (?, ?, 'en-CA', 'active')")
                .params(owner, name + " Owner")
                .update();
        jdbc.sql("""
                        insert into merchants.merchants (id, type, display_name, legal_name, structure, tier, status, city,
                               profile, approved_at)
                        values (?, 'provider', ?, ?, 'sole', ?, 'active', 'Calgary',
                                '{"description":"We come to you."}'::jsonb, now() - interval '400 days')
                        """).params(merchantId, name, name + " Ltd.", tier).update();
        jdbc.sql(
                        "insert into merchants.merchant_members (merchant_id, user_id, role, bookable, mfa_ok) values (?, ?, 'owner', true, true)")
                .params(merchantId, owner)
                .update();
        jdbc.sql("""
                        insert into merchants.storefronts (id, merchant_id, slug, page_kind, brand_color, tagline_i18n,
                               cta_label, published_at)
                        values (?, ?, ?, 'business_page', '#2f5d3a', '{"en":"Mobile mechanic · Calgary"}', 'book_visit', now())
                        """).params(Ids.next(), merchantId, slug).update();
        for (int weekday = 1; weekday <= 7; weekday++) {
            jdbc.sql("""
                            insert into availability.availability_rules (id, merchant_id, member_user_id, weekday, ranges,
                                   effective_from)
                            values (?, ?, ?, ?, '[["00:00","23:30"]]'::jsonb, current_date - 30)
                            """).params(Ids.next(), merchantId, owner, weekday).update();
        }
        jdbc.sql("""
                        insert into availability.booking_rules (merchant_id, interval_min, buffer_min, min_notice_min,
                               horizon_days, max_jobs_per_day, accept_mode, reschedule_free_min, late_cancel_fee_cents)
                        values (?, 30, 0, 60, 14, 99, 'instant', 180, 0)
                        """).params(merchantId).update();
        zones.forEach(z -> jdbc.sql("insert into availability.service_areas (merchant_id, zone) values (?, ?)")
                .params(merchantId, z)
                .update());
        return new Provider(merchantId, slug, owner);
    }

    /** A live, approved service. */
    public String service(
            String merchantId,
            String categoryId,
            String name,
            String pricingMode,
            @Nullable Long priceCents,
            int durationMin) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into catalogue.services (id, merchant_id, category_id, name, name_i18n, included,
                               pricing_mode, price_cents, duration_min, buffer_min, instant_book, vetting, status)
                        values (?, ?, ?, ?, jsonb_build_object('en', ?::text), 'Written report with photos.', ?, ?, ?, 0,
                                true, 'approved', 'live')
                        """)
                .params(id, merchantId, categoryId, name, name, pricingMode, priceCents, durationMin)
                .update();
        return id;
    }

    public void quality(String merchantId, double onTime, double disputes, double rebook) {
        jdbc.sql("""
                        insert into trust.quality_scores (merchant_id, date, score, components)
                        values (?, current_date, 90, jsonb_build_object(
                          'on_time', jsonb_build_object('value', ?::numeric, 'floor', 95),
                          'photos', jsonb_build_object('value', 90, 'floor', 80),
                          'response', jsonb_build_object('value', 90, 'floor', 80),
                          'rebook', jsonb_build_object('value', ?::numeric, 'floor', 40),
                          'disputes', jsonb_build_object('value', ?::numeric, 'floor', 1)))
                        """).params(merchantId, onTime, rebook, disputes).update();
    }

    /** A verified review of the business, {@code daysAgo} days old. */
    public String review(String merchantId, int rating, String text, String author, int daysAgo) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating, text,
                               author_name, job_label, created_at)
                        values (?, 'booking', ?, ?, 'merchant', ?, ?, ?, ?, 'Brake inspection', now() - make_interval(days => ?))
                        """)
                .params(id, Ids.next(), Ids.next(), merchantId, rating, text, author, daysAgo)
                .update();
        return id;
    }

    public void verified(String merchantId, String checkKey) {
        jdbc.sql("""
                        insert into merchants.verifications (id, merchant_id, check_type, check_key, status, position)
                        values (?, ?, split_part(?, ':', 1), ?, 'verified', 0)
                        """).params(Ids.next(), merchantId, checkKey, checkKey).update();
    }

    public void unpublish(String merchantId) {
        jdbc.sql("update merchants.storefronts set published_at = null where merchant_id = ?")
                .params(merchantId)
                .update();
    }
}
