package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.payments.application.StripeEvent;
import ca.northline.payments.application.StripeEventStore;
import ca.northline.payments.application.StripeObject;
import ca.northline.shared.CodedEnum;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code payments.stripe_events}: the primary key on Stripe's event id is the dedupe. */
@Repository
@RequiredArgsConstructor
class StripeEventJdbcStore implements StripeEventStore {

    private final JdbcClient jdbc;

    @Override
    public boolean insert(StripeEvent event, Instant receivedAt) {
        return jdbc.sql("""
                        insert into payments.stripe_events
                               (id, type, endpoint, account, livemode, object_id, created_at, received_at, payload, state)
                        values (:id, :type, :endpoint, :account, :livemode, :object, :created, :received,
                                cast(:payload as jsonb), 'received')
                        on conflict (id) do nothing""")
                        .param("id", event.id())
                        .param("type", event.type())
                        .param("endpoint", event.endpoint().code())
                        .param("account", event.account())
                        .param("livemode", event.livemode())
                        .param("object", event.object().id())
                        .param("created", ts(event.created()))
                        .param("received", ts(receivedAt))
                        .param("payload", event.object().redacted())
                        .update()
                > 0;
    }

    @Override
    public Optional<Stored> lock(String eventId) {
        return jdbc.sql("""
                        select id, type, endpoint, account, livemode, created_at, payload, state, attempts
                          from payments.stripe_events where id = :id for update""")
                .param("id", eventId)
                .query((rs, _) -> new Stored(
                        new StripeEvent(
                                rs.getString("id"),
                                rs.getString("type"),
                                CodedEnum.fromCode(StripeEvent.Endpoint.class, rs.getString("endpoint")),
                                rs.getString("account"),
                                rs.getBoolean("livemode"),
                                requiredInstant(rs, "created_at"),
                                StripeObject.parse(rs.getString("payload"))),
                        State.valueOf(rs.getString("state").toUpperCase(Locale.ROOT)),
                        rs.getInt("attempts")))
                .optional();
    }

    @Override
    public void finish(String eventId, State state, Instant at, @Nullable String note) {
        jdbc.sql("""
                        update payments.stripe_events
                           set state = :state, processed_at = :at, attempts = attempts + 1, error = :note
                         where id = :id""")
                .param("state", state.name().toLowerCase(Locale.ROOT))
                .param("at", ts(at))
                .param("note", note)
                .param("id", eventId)
                .update();
    }

    @Override
    public void failed(String eventId, String error, Instant at) {
        jdbc.sql("""
                        update payments.stripe_events
                           set state = 'failed', attempts = attempts + 1, error = left(:error, 2000), processed_at = :at
                         where id = :id and state in ('received', 'failed')""")
                .param("error", error)
                .param("at", ts(at))
                .param("id", eventId)
                .update();
    }

    @Override
    public List<String> pending(int maxAttempts, int limit) {
        return jdbc.sql("""
                        select id from payments.stripe_events
                         where state in ('received', 'failed') and attempts < :max
                         order by created_at, received_at limit :limit""")
                .param("max", maxAttempts)
                .param("limit", limit)
                .query((rs, _) -> rs.getString("id"))
                .list();
    }

    @Override
    public int purge(Instant before) {
        return jdbc.sql(
                        "delete from payments.stripe_events where state in ('processed', 'ignored') and received_at < :before")
                .param("before", ts(before))
                .update();
    }
}
