package ca.northline.catalogue.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S-77: the local media store serves the bundled sample pictures for dev-seed keys until something is uploaded. */
class LocalMediaStorageTest {

    @TempDir
    Path root;

    @Test
    void servesBundledSamplePictureForDevSeedKeys_untilOneIsUploaded() {
        var store = new LocalMediaStorage(root);
        assertThat(store.get("seed/country-sourdough.jpg"))
                .hasValueSatisfying(bytes -> assertThat(bytes).hasSizeGreaterThan(1000));
        store.put("seed/country-sourdough.jpg", new byte[] {1, 2, 3}, "image/jpeg");
        assertThat(store.get("seed/country-sourdough.jpg")).hasValue(new byte[] {1, 2, 3});
    }

    @Test
    void unknownOrUnsafeSeedKeysAreNotServed() {
        var store = new LocalMediaStorage(root);
        assertThat(store.get("seed/missing.jpg")).isEmpty();
        assertThat(store.get("seed/Country Sourdough.JPG")).isEmpty();
        assertThatThrownBy(() -> store.get("seed/../../application.yml")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get("merchants/x/country-sourdough.jpg")).isEmpty();
    }
}
