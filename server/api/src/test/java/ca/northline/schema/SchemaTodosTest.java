package ca.northline.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-70: the baseline's "TODO indexes/constraints" that V181 materialised exist on the migrated database, and the audit
 * log is append-only except for the seven-year retention purge.
 */
class SchemaTodosTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void theBaselineTodoIndexesExist() {
        var indexes = jdbc.sql("select schemaname || '.' || indexname from pg_indexes")
                .query(String.class)
                .set();
        assertThat(indexes)
                .contains(
                        "identity.ix_addresses_geom",
                        "catalogue.ix_categories_parent",
                        "catalogue.ix_categories_search_terms",
                        "catalogue.ix_services_merchant",
                        "catalogue.ix_services_category",
                        "orders.ux_group_orders_link_code",
                        "fulfilment.ix_runs_courier_state",
                        "fulfilment.ix_stops_run_seq",
                        "messaging.ix_notifications_user_sent");
    }

    @Test
    void aGroupOrderLinkResolvesToOneGroupOrder() {
        var code = "LNK-" + Ids.next();
        jdbc.sql("insert into orders.group_orders (id, link_code) values (?, ?)")
                .params(Ids.next(), code)
                .update();
        assertThatThrownBy(() -> jdbc.sql("insert into orders.group_orders (id, link_code) values (?, ?)")
                        .params(Ids.next(), code)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theAuditLogIsAppendOnly() {
        var id = Ids.next();
        jdbc.sql("insert into developer.audit_log (id, actor_id, action, at) values (?, ?, 'test.action', now())")
                .params(id, Ids.next())
                .update();
        for (var change : List.of(
                "update developer.audit_log set action = 'changed' where id = ?",
                "delete from developer.audit_log where id = ?")) {
            assertThatThrownBy(() -> jdbc.sql(change).params(id).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("append-only");
        }
        // the retention purge may remove rows past seven years, and only when it says so
        assertThatThrownBy(() -> tx.executeWithoutResult(_ -> {
                    jdbc.sql("set local northline.audit_retention = 'on'").update();
                    jdbc.sql("delete from developer.audit_log where id = ?")
                            .params(id)
                            .update();
                }))
                .hasMessageContaining("append-only");
        var old = Ids.next();
        jdbc.sql("insert into developer.audit_log (id, action, at) values (?, 'test.old', now() - interval '8 years')")
                .params(old)
                .update();
        assertThatThrownBy(() -> jdbc.sql("delete from developer.audit_log where id = ?")
                        .params(old)
                        .update())
                .hasMessageContaining("append-only");
        tx.executeWithoutResult(_ -> {
            jdbc.sql("set local northline.audit_retention = 'on'").update();
            assertThat(jdbc.sql("delete from developer.audit_log where id = ?")
                            .params(old)
                            .update())
                    .isEqualTo(1);
        });
    }
}
