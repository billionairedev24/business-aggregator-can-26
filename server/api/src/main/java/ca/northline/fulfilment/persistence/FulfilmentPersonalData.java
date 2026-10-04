package ca.northline.fulfilment.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, fulfilment: the person's deliveries (as a customer) and their courier record and shifts (as a courier).
 *
 * <p>Erasure: a finished delivery's drop-off address and instructions go; the delivery and its proof (photo, signature
 * or PIN, under {@code fulfilment/proofs/}) stay as evidence for "not received" chargebacks. A delivery still on its
 * way holds the erasure. A courier is made inactive; their runs and shifts stay Northline's records of work done.
 */
@Component
@RequiredArgsConstructor
class FulfilmentPersonalData implements PersonalDataContributor {

    static final String ACTIVE = "('waiting', 'planned', 'picked_up', 'returning')";

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "fulfilment";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(jdbc, "fulfilment.deliveries", "Deliveries", "Livraisons", """
                        select order_id, order_ref, order_type, kind, market, state, starts_at, ends_at, dropoff,
                               created_at
                          from fulfilment.deliveries where customer_id = :u order by created_at
                        """, p),
                section(jdbc, "fulfilment.courier", "Courier profile", "Profil de livreur", """
                        select id, vehicle, status, rating, market, active, created_at
                          from fulfilment.couriers where user_id = :u
                        """, p),
                section(jdbc, "fulfilment.shifts", "Courier shifts", "Quarts de livraison", """
                        select s.id, s.starts_at, s.ends_at, s.state, s.started_at, s.ended_at
                          from fulfilment.shifts s join fulfilment.couriers c on c.id = s.courier_id
                         where c.user_id = :u order by s.starts_at
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        var active = jdbc.sql(
                        "select count(*) from fulfilment.deliveries where customer_id = :u and state in " + ACTIVE)
                .param("u", u)
                .query(Integer.class)
                .single();
        var delivered = jdbc.sql("""
                        update fulfilment.deliveries set dropoff = null, updated_at = now()
                         where customer_id = :u and dropoff is not null and state not in
                        """ + ACTIVE).param("u", u).update();
        var courier = jdbc.sql("""
                        update fulfilment.couriers set active = false, status = 'offline' where user_id = :u
                        """).param("u", u).update();
        jdbc.sql("""
                        update fulfilment.shifts set state = 'cancelled'
                         where state = 'scheduled' and courier_id in (select id from fulfilment.couriers where user_id = :u)
                        """).param("u", u).update();
        var outcome = Erasure.done();
        if (delivered > 0 || active > 0) {
            outcome = outcome.retaining("fulfilment.deliveries.proofs", Retention.CHARGEBACK_EVIDENCE);
        }
        if (courier > 0) {
            outcome = outcome.retaining("fulfilment.courierRuns", Retention.BUSINESS_RECORDS);
        }
        return active > 0 ? outcome.holding("fulfilment.deliveries", Hold.ACTIVE_DELIVERY) : outcome;
    }
}
