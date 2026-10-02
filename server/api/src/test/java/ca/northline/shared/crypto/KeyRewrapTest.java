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

    @Test
    void theModulesDeclareTheirSealedColumns_andTheQueriesRunOnTheRealSchema() {
        assertThat(appRewrap.stale())
                .containsOnlyKeys(
                        "availability.calendar_links",
                        "catalogue.integrations",
                        "food.pos_connections",
                        "booking.access_notes");
        assertThat(appRewrap.run().values())
                .allSatisfy(o -> assertThat(o.failed()).isZero());
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
