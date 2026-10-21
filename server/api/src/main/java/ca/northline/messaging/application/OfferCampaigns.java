package ca.northline.messaging.application;

import ca.northline.messaging.domain.OfferDraft;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * S-157, a minimal producer of Northline's commercial messages: staff write an offer (en + fr, a page of the site),
 * look at it as the customers will get it, send themselves a test, see how many customers of the market agreed to that
 * channel, and send it — throttled through the worker, which re-checks each person's consent at send time and adds the
 * sender identification and the unsubscribe link (CASL s. 6(2)). Console screen {@code offers}; audit-logged.
 */
public interface OfferCampaigns {

    /** The email (both languages) and the text (both languages) as they will go out, and who would get them. */
    record Preview(Rendered en, Rendered fr, Audience audience) {}

    /** @param smsSegments 160-character parts of the text (a GSM-7 estimate; accents count as in UCS-2 below 70) */
    record Rendered(String subject, String html, String text, String sms, int smsSegments) {}

    /** Customers of the market whose current consent covers each channel. */
    record Audience(String market, int email, int sms) {}

    /**
     * A sent offer and how far the worker got: {@code queued} at send time, {@code pending} still waiting (throttle,
     * quiet hours, an outage), {@code givenUp} dead rows (S-115).
     */
    record Campaign(
            String id,
            String market,
            String channel,
            String titleEn,
            String titleFr,
            String path,
            int recipients,
            int pending,
            int givenUp,
            String sentBy,
            Instant sentAt,
            Instant finishesAt) {}

    record Overview(Set<String> markets, int perMinute, List<Campaign> campaigns) {}

    Overview overview();

    Preview preview(OfferDraft draft);

    /** Sends the offer to the staff member's own address (or mobile) only, marked as a test. */
    void test(OfferDraft draft, String staffId, String staffRoles, String language);

    /** Queues the offer for every consenting customer of the market (409 {@code no_recipients} when there is none). */
    Campaign send(OfferDraft draft, String staffId, String staffRoles);

    /** Port: {@code messaging.offer_campaigns} (V454), the consent records and the worker's deferred queue. */
    interface OfferStore {

        /** People whose newest record for the category is a customer-facing grant (Account wording or keyword). */
        List<String> consentingCustomers(String category);

        void save(Campaign campaign, OfferDraft draft, int perMinute);

        /** One deferred row per recipient: {@code messaging.offer}, due from {@code start}, {@code perMinute} a minute. */
        Instant enqueue(String campaignId, OfferDraft draft, List<String> recipients, Instant start, int perMinute);

        List<Campaign> recent(int limit);
    }
}
