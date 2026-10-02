package ca.northline.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The {@link ObjectStore} contract, run against every provider (local folder, S3-compatible, GCS, Azure Blob). Each
 * subclass hands over the provider; the tests go through {@link GuardedObjectStore}, as the application does.
 */
abstract class ObjectStoreContract {

    private static final String PDF = "application/pdf";

    /** The provider under test, freshly built or shared; keys never repeat across tests. */
    abstract ObjectStore provider();

    ObjectStore store;
    String merchantId;

    @BeforeEach
    void setUp() {
        store = new GuardedObjectStore(provider(), VirusScanner.NONE);
        merchantId = Ids.next();
    }

    private String key(String contentType) {
        return "contract/" + ObjectKeys.merchantObject(merchantId, Ids.next(), contentType);
    }

    private static byte[] randomBytes(int size) {
        var bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    @Test
    void storesAndReturnsBytesWithContentTypeAndSize() {
        var key = key("image/png");
        var bytes = randomBytes(300_000);

        var stored = store.put(key, bytes, "image/png");

        assertThat(stored).isEqualTo(new ObjectStore.ObjectInfo(key, "image/png", 300_000));
        var content = store.get(key).orElseThrow();
        assertThat(content.bytes().toArray()).isEqualTo(bytes);
        assertThat(content.info()).isEqualTo(stored);
        assertThat(store.info(key)).contains(stored);
        assertThat(store.exists(key)).isTrue();
    }

    @Test
    void missingObjectIsEmptyNotAnError() {
        var key = key(PDF);
        assertThat(store.get(key)).isEmpty();
        assertThat(store.info(key)).isEmpty();
        assertThat(store.exists(key)).isFalse();
        store.delete(key);
    }

    @Test
    void putReplacesAnExistingObject() {
        var key = key(PDF);
        store.put(key, "first".getBytes(StandardCharsets.UTF_8), PDF);
        store.put(key, "second version".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        var content = store.get(key).orElseThrow();
        assertThat(new String(content.bytes().toArray(), StandardCharsets.UTF_8))
                .isEqualTo("second version");
        assertThat(content.info().contentType()).isEqualTo("image/jpeg");
        assertThat(content.info().size()).isEqualTo(14);
    }

    @Test
    void deleteRemovesTheObjectAndIsIdempotent() {
        var key = key(PDF);
        store.put(key, randomBytes(10), PDF);

        store.delete(key);
        store.delete(key);

        assertThat(store.exists(key)).isFalse();
        assertThat(store.get(key)).isEmpty();
    }

    @Test
    void deleteAllRemovesEverythingUnderAPrefixOnly() {
        var person = "contract/customers/" + Ids.next();
        var kept = "contract/customers/" + Ids.next() + "/" + Ids.next() + ".pdf";
        store.put(person + "/" + Ids.next() + ".pdf", randomBytes(10), PDF);
        store.put(person + "/cases/" + Ids.next() + ".png", randomBytes(20), "image/png");
        store.put(kept, randomBytes(10), PDF);
        var within = store.within(person);
        within.put("note.pdf", randomBytes(5), PDF);

        assertThat(store.deleteAll(person)).isEqualTo(3);
        assertThat(store.deleteAll(person)).isZero();
        assertThat(within.exists("note.pdf")).isFalse();
        assertThat(store.exists(kept)).isTrue();
        assertThat(within.deleteAll("cases")).isZero();
    }

    @Test
    void emptyObject() {
        var key = key(PDF);
        store.put(key, new byte[0], PDF);
        assertThat(store.get(key).orElseThrow().bytes().isEmpty()).isTrue();
        assertThat(store.info(key).orElseThrow().size()).isZero();
    }

    @Test
    void withinNamespacesKeysUnderAPrefix() {
        var module = store.within("contract").within("module");
        var relative = ObjectKeys.merchantObject(merchantId, Ids.next(), PDF);

        var stored = module.put(relative, randomBytes(64), PDF);

        assertThat(stored.key()).isEqualTo(relative);
        assertThat(module.get(relative).orElseThrow().info().key()).isEqualTo(relative);
        assertThat(store.exists("contract/module/" + relative)).isTrue();
        assertThat(store.exists("contract/" + relative)).isFalse();
    }

    @Test
    void presignedGetServesTheBytes() throws Exception {
        var key = key("image/jpeg");
        var bytes = randomBytes(4_096);
        store.put(key, bytes, "image/jpeg");

        var url = store.presignGet(key, Duration.ofMinutes(5));

        assertThat(fetch(url)).isEqualTo(bytes);
    }

    @Test
    void presignTtlIsBounded() {
        var key = key(PDF);
        assertThatThrownBy(() -> store.presignGet(key, Duration.ofHours(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.presignGet(key, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "/absolute", "a//b", "a/../b", "trailing/", ".hidden", "sp ace", ""})
    void refusesInvalidKeys(String key) {
        assertThatThrownBy(() -> store.put(key, randomBytes(1), PDF)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInfectedUploadIsRejectedAndNotStored() {
        var scanned = new GuardedObjectStore(
                provider(),
                (_, _, bytes) -> bytes.length > 0 && bytes[0] == 'X'
                        ? new VirusScanner.Verdict.Infected("EICAR-Test-File")
                        : new VirusScanner.Verdict.Clean());
        var key = key(PDF);

        assertThatThrownBy(() -> scanned.put(key, "X5O!P%@AP".getBytes(StandardCharsets.US_ASCII), PDF))
                .isInstanceOfSatisfying(
                        RuleViolation.class,
                        ex -> assertThat(ex.getViolations())
                                .containsExactly(
                                        new RuleViolation.Violation("file", "virus", GuardedObjectStore.REJECTED)));
        assertThat(scanned.exists(key)).isFalse();

        scanned.put(key, "%PDF-1.7".getBytes(StandardCharsets.US_ASCII), PDF);
        assertThat(scanned.exists(key)).isTrue();
    }

    static byte[] fetch(URI url) throws Exception {
        if ("file".equals(url.getScheme())) {
            return Files.readAllBytes(Path.of(url));
        }
        try (var http = HttpClient.newHttpClient()) {
            var response =
                    http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).as(url.toString()).isEqualTo(200);
            return response.body();
        }
    }
}
