package ca.northline.tools.demo;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Wipes the demo data (S-194, {@code RESET=true}): every row whose id or any {@code *_id} column holds a demo id
 * ({@link DemoIds#PREFIX}) — the rows the seed made and the rows people made with them through the product (orders a
 * demo customer placed, bookings with a demo provider, their payments). Tables are emptied in passes until nothing
 * more can go: a row still referenced from a table not emptied yet is retried in the next pass. Append-only tables
 * ({@code payments.ledger_entries}, {@code developer.audit_log}) and what their triggers protect stay — V016/V061/V015
 * are never bypassed — and so does the event outbox. The market stays live.
 */
@Slf4j
final class DemoReset {

    /** Never touched: the event outbox, the OAuth tables and the append-only ledgers (their triggers refuse a delete). */
    static final Set<String> KEEP = Set.of(
            "payments.ledger_entries", "developer.audit_log", "events.event_publication", "events.event_publication_archive",
            "events.processed_events", "auth.event_publication", "auth.event_publication_archive");

    private static final Set<String> SCHEMAS_SKIPPED = Set.of("pg_catalog", "information_schema", "public", "topology", "tiger", "tiger_data");

    private final Db db;

    DemoReset(Db db) {
        this.db = db;
    }

    /** Deletes; returns rows deleted per table (only tables that had some). */
    Map<String, Integer> run() {
        var tables = tables();
        var deleted = new LinkedHashMap<String, Integer>();
        var pending = new ArrayList<>(tables.entrySet());
        for (int pass = 1; pass <= 12 && !pending.isEmpty(); pass++) {
            var next = new ArrayList<Map.Entry<String, List<String>>>();
            boolean progress = false;
            for (var entry : pending) {
                var table = entry.getKey();
                var where = String.join(" or ", entry.getValue().stream().map(c -> c + " like '" + DemoIds.LIKE + "'").toList());
                try {
                    var sp = db.connection().setSavepoint();
                    try (var st = db.connection().createStatement()) {
                        var n = st.executeUpdate("delete from " + table + " where " + where);
                        db.connection().releaseSavepoint(sp);
                        if (n > 0) {
                            deleted.merge(table, n, Integer::sum);
                            progress = true;
                        }
                    } catch (SQLException e) {
                        db.connection().rollback(sp);
                        if ("23503".equals(e.getSQLState())) {
                            next.add(entry); // still referenced: try again after the others
                        } else {
                            log.warn("Kept the demo rows of {} ({})", table, String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
                        }
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            pending = next;
            if (!progress) {
                break;
            }
        }
        if (!pending.isEmpty()) {
            log.warn("Demo rows still referenced from outside the demo, kept in: {}", pending.stream().map(Map.Entry::getKey).toList());
        }
        return deleted;
    }

    /** Every application table with its text columns that can hold an id ({@code id}, {@code *_id}). */
    private Map<String, List<String>> tables() {
        var out = new LinkedHashMap<String, List<String>>();
        db.list(
                        """
                        select c.table_schema || '.' || c.table_name as t, c.column_name
                          from information_schema.columns c
                          join information_schema.tables tb on tb.table_schema = c.table_schema and tb.table_name = c.table_name
                         where tb.table_type = 'BASE TABLE' and c.data_type = 'text'
                           and (c.column_name = 'id' or c.column_name like '%\\_id' or c.column_name in ('created_by', 'uploaded_by', 'submitted_by'))
                         order by 1, 2
                        """,
                        rs -> new String[] {rs.getString(1), rs.getString(2)})
                .forEach(r -> {
                    var schema = r[0].substring(0, r[0].indexOf('.'));
                    if (!SCHEMAS_SKIPPED.contains(schema) && !KEEP.contains(r[0])) {
                        out.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r[1]);
                    }
                });
        return out;
    }
}
