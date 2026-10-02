package ca.northline.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.support.IntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-115 runbook drill (docs/runbooks/key-rotation.md § KMS data keys), on the migrated database: values sealed under
 * the old local key, the api restarted with a new {@code KMS_LOCAL_KEY} and the old one in
 * {@code KMS_LOCAL_PREVIOUS_KEYS}, the re-wrap job moves every data key to the new key without touching the
 * ciphertext, the old key can then go — and the modules' declared columns are real columns.
 */
class KeyRewrapTest extends IntegrationTest {

    static final byte[] OLD = Base64.getDecoder().decode(CryptoConfiguration.DEV_KEY);
    static final byte[] NEW = "s115-fake-rotated-local-key-32by".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KeyRewrap appRewrap;

    @Autowired
    List<SealedColumn> columns;

    @Autowired
    SecretSealer appSealer;

    /**
     * Every module's declared column, on the migrated schema, through a rotation: a privacy request (S-105) sealed under
     * an old key is re-wrapped with its module's context and opens with the api's own sealer afterwards. Rows other test
     * classes left with placeholder bytes (not envelopes) may fail; anything else failing names its table and rows.
     */
    @Test
    void theModulesDeclareTheirSealedColumns_andARotationReWrapsThemOnTheRealSchema() {
        assertThat(appRewrap.stale())
                .containsOnlyKeys(
                        "availability.calendar_links",
                        "catalogue.integrations",
                        "food.pos_connections",
                        "booking.access_notes",
                        "privacy.requests");

        var request = ca.northline.shared.Ids.next();
        var context = ca.northline.privacy.application.PrivacyRequestStore.SEALED_CONTEXT + request;
        var old = new EnvelopeSealer(new KeyWrappers.Local(NEW)).seal("{\"email\":\"dana@example.test\"}", context);
        jdbc.sql("""
                        insert into privacy.requests (id, subject_id, subject_kind, type, state, channel, province, law,
                                                      received_at, due_at, sealed_key_ref, sealed_key, sealed_data)
                        values (:id, 'u-s115', 'customer', 'access', 'verified', 'self', 'AB', 'pipeda', now(),
                                now() + interval '30 days', :ref, :key, :data)""")
                .param("id", request)
                .param("ref", old.keyRef())
                .param("key", old.wrappedKey())
                .param("data", old.ciphertext())
                .update();
        try {
            // the api restarted with its own key current and NEW as a previous key
            var rewrap = new KeyRewrap(
                    new EnvelopeSealer(new KeyWrappers.Local(OLD, List.of(NEW))),
                    jdbc,
                    columns,
                    new SimpleMeterRegistry(),
                    10_000);
            var outcomes = rewrap.run();
            assertThat(outcomes.get("privacy.requests").rewrapped()).isPositive();
            for (var column : columns) {
                var outcome = outcomes.get(column.table());
                assertThat(outcome.failedRows()).hasSize(Math.min(outcome.failed(), 20));
                for (var row : outcome.failedRows()) {
                    assertThat(wrappedKeyLength(column, row))
                            .as("%s.%s row %s: an envelope the re-wrap couldn't move", column.table(), column.wrappedKey(), row)
                            .isLessThan(28); // a real wrapped key is 60 bytes; test fixtures write 1-byte placeholders
                }
            }
            var after = jdbc.sql("select sealed_key_ref, sealed_key, sealed_data from privacy.requests where id = :id")
                    .param("id", request)
                    .query((rs, _) -> new SecretSealer.Sealed(rs.getString(1), rs.getBytes(2), rs.getBytes(3)))
                    .single();
            assertThat(after.keyRef()).isNotEqualTo(old.keyRef());
            assertThat(appSealer.open(after, context)).contains("dana@example.test");
        } finally {
            jdbc.sql("delete from privacy.requests where id = :id").param("id", request).update();
        }
    }

    private int wrappedKeyLength(SealedColumn c, String id) {
        return jdbc.sql("select coalesce(octet_length(%s), 0) from %s where %s = :id"
                        .formatted(c.wrappedKey(), c.table(), c.id()))
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    @Test
    void rotateTheLocalKey_rewrapEverything_thenTheOldKeyCanGo() {
        jdbc.sql("""
                        create table if not exists public.s115_rewrap_drill (
                          id text primary key, key_ref text, wrapped_key bytea, ciphertext bytea)""").update();
        jdbc.sql("truncate public.s115_rewrap_drill").update();
        var column =
                new SealedColumn("public.s115_rewrap_drill", "id", "key_ref", "wrapped_key", "ciphertext", "drill:");
        var before = new EnvelopeSealer(new KeyWrappers.Local(OLD));
        for (var id : List.of("a", "b", "c")) {
            var sealed = before.seal("fake-token-" + id, "drill:" + id);
            jdbc.sql("insert into public.s115_rewrap_drill values (:id, :ref, :key, :enc)")
                    .param("id", id)
                    .param("ref", sealed.keyRef())
                    .param("key", sealed.wrappedKey())
                    .param("enc", sealed.ciphertext())
                    .update();
        }
        var ciphertexts = ciphertexts();

        // the restart: KMS_LOCAL_KEY=<new>, KMS_LOCAL_PREVIOUS_KEYS=<old>
        var after = new EnvelopeSealer(new KeyWrappers.Local(NEW, List.of(OLD)));
        var meters = new SimpleMeterRegistry();
        var rewrap = new KeyRewrap(after, jdbc, List.of(column), meters, 2);
        assertThat(rewrap.stale()).containsEntry("public.s115_rewrap_drill", 3);
        assertThat(after.open(read("a"), "drill:a")).isEqualTo("fake-token-a"); // still readable meanwhile

        assertThat(rewrap.run()).containsEntry("public.s115_rewrap_drill", new KeyRewrap.Outcome(2, 0)); // batch 2
        assertThat(rewrap.run()).containsEntry("public.s115_rewrap_drill", new KeyRewrap.Outcome(1, 0));
        assertThat(rewrap.run()).containsEntry("public.s115_rewrap_drill", new KeyRewrap.Outcome(0, 0));
        assertThat(rewrap.stale()).containsEntry("public.s115_rewrap_drill", 0);
        assertThat(meters.counter(KeyRewrap.METRIC, "table", "public.s115_rewrap_drill", "outcome", "rewrapped")
                        .count())
                .isEqualTo(3.0);
        assertThat(ciphertexts()).isEqualTo(ciphertexts); // only the data keys moved

        // KMS_LOCAL_PREVIOUS_KEYS emptied: the new key alone opens everything
        var newOnly = new EnvelopeSealer(new KeyWrappers.Local(NEW));
        assertThat(newOnly.currentKeyRef()).isEqualTo(read("a").keyRef());
        for (var id : List.of("a", "b", "c")) {
            assertThat(newOnly.open(read(id), "drill:" + id)).isEqualTo("fake-token-" + id);
        }
        jdbc.sql("drop table public.s115_rewrap_drill").update();
    }

    private SecretSealer.Sealed read(String id) {
        return jdbc.sql("select key_ref, wrapped_key, ciphertext from public.s115_rewrap_drill where id = :id")
                .param("id", id)
                .query((rs, _) -> new SecretSealer.Sealed(rs.getString(1), rs.getBytes(2), rs.getBytes(3)))
                .single();
    }

    private Map<String, String> ciphertexts() {
        return jdbc
                .sql("select id, encode(ciphertext, 'hex') from public.s115_rewrap_drill")
                .query((rs, _) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
