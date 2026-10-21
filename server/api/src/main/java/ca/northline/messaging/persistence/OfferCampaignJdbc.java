package ca.northline.messaging.persistence;

import ca.northline.messaging.application.OfferCampaigns.Campaign;
import ca.northline.messaging.application.OfferCampaigns.OfferStore;
import ca.northline.messaging.domain.OfferDraft;
import ca.northline.shared.Ids;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-157: {@code messaging.offer_campaigns} (V454), the consent records' current grants, and the worker's queue {@code
 * messaging.deferred_notifications} (V074; until now written only by the worker): one {@code messaging.offer} row per
 * recipient — the payload is {@code PersonalNotices.offer}'s (the worker reads it back at send time) plus the campaign
 * id; the row's channel is the only one the worker tries.
 */
@Repository
@RequiredArgsConstructor
class OfferCampaignJdbc implements OfferStore {

    static final String OFFER = "messaging.offer";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    @Override
    public List<String> consentingCustomers(String category) {
        return jdbc.sql("""
                        select user_id from (
                          select distinct on (user_id) user_id, action, wording_version
                            from messaging.consent_records
                           where category = :category
                           order by user_id, at desc, id desc) latest
                         where action = 'granted'
                           and (wording_version like 'account.%' or wording_version like 'keyword.%')""")
                .param("category", category)
                .query((rs, _) -> rs.getString(1))
                .list();
    }

    @Override
    public void save(Campaign c, OfferDraft d, int perMinute) {
        jdbc.sql("""
                        insert into messaging.offer_campaigns (id, market, channel, title_en, body_en, title_fr, body_fr,
                               path, recipients, per_minute, sent_by, sent_at, finishes_at)
                        values (:id, :market, :channel, :titleEn, :bodyEn, :titleFr, :bodyFr, :path, :recipients,
                               :perMinute, :by, :at, :finishes)""")
                .param("id", c.id())
                .param("market", d.market())
                .param("channel", d.channel())
                .param("titleEn", d.titleEn())
                .param("bodyEn", d.bodyEn())
                .param("titleFr", d.titleFr())
                .param("bodyFr", d.bodyFr())
                .param("path", d.path())
                .param("recipients", c.recipients())
                .param("perMinute", perMinute)
                .param("by", c.sentBy())
                .param("at", utc(c.sentAt()))
                .param("finishes", utc(c.finishesAt()))
                .update();
    }

    @Override
    public Instant enqueue(String campaignId, OfferDraft d, List<String> recipients, Instant start, int perMinute) {
        var last = start;
        for (var i = 0; i < recipients.size(); i++) {
            var user = recipients.get(i);
            var due = start.plus(Duration.ofMinutes(i / perMinute));
            last = due;
            var payload = new LinkedHashMap<String, Object>();
            payload.put("aggregateId", user);
            payload.put("customerId", user);
            payload.put("titleEn", d.titleEn());
            payload.put("bodyEn", d.bodyEn());
            payload.put("titleFr", d.titleFr());
            payload.put("bodyFr", d.bodyFr());
            payload.put("path", d.path());
            payload.put("campaignId", campaignId);
            jdbc.sql("""
                            insert into messaging.deferred_notifications (id, channel, audience, user_id, merchant_id,
                                   event_id, event_type, event_version, payload, due_at)
                            values (:id, :channel, 'customer', :user, null, :event, :type, 1, cast(:payload as jsonb),
                                   :due)
                            on conflict (channel, user_id, event_id) do nothing""")
                    .param("id", Ids.next())
                    .param("channel", d.channel())
                    .param("user", user)
                    .param("event", campaignId)
                    .param("type", OFFER)
                    .param("payload", json.writeValueAsString(payload))
                    .param("due", utc(due))
                    .update();
        }
        return last;
    }

    @Override
    public List<Campaign> recent(int limit) {
        return jdbc.sql("""
                        select c.id, c.market, c.channel, c.title_en, c.title_fr, c.path, c.recipients, c.sent_by,
                               c.sent_at, c.finishes_at,
                               count(d.id) filter (where d.dead_at is null) as pending,
                               count(d.id) filter (where d.dead_at is not null) as given_up
                          from messaging.offer_campaigns c
                          left join messaging.deferred_notifications d
                            on d.event_id = c.id and d.event_type = 'messaging.offer'
                         group by c.id
                         order by c.sent_at desc, c.id desc
                         limit :limit""")
                .param("limit", limit)
                .query((rs, _) -> new Campaign(
                        rs.getString("id"),
                        rs.getString("market"),
                        rs.getString("channel"),
                        rs.getString("title_en"),
                        rs.getString("title_fr"),
                        rs.getString("path"),
                        rs.getInt("recipients"),
                        rs.getInt("pending"),
                        rs.getInt("given_up"),
                        rs.getString("sent_by"),
                        rs.getObject("sent_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("finishes_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}
