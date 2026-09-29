package ca.northline.food.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalKitchenPhotoStoreTest {

    @TempDir
    Path root;

    @Test
    void servesBundledSamplePhotoForDevSeedKeys() {
        var store = new LocalKitchenPhotoStore(root);
        assertThat(store.get("seed/pho-dac-biet.jpg"))
                .hasValueSatisfying(bytes -> assertThat(bytes).hasSizeGreaterThan(1000));
    }

    @Test
    void unknownOrUnsafeSeedKeysAreNotServed() {
        var store = new LocalKitchenPhotoStore(root);
        assertThat(store.get("seed/missing-dish.jpg")).isEmpty();
        assertThat(store.get("seed/Pho Dac Biet.JPG")).isEmpty();
        assertThatThrownBy(() -> store.get("seed/../../application.yml")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get("merchants/x/item.jpg")).isEmpty();
    }
}
