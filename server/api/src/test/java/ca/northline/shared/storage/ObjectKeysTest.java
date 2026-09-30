package ca.northline.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ObjectKeysTest {

    @Test
    void merchantObjectsAreMerchantIdSlashIdWithTheTypesExtension() {
        assertThat(ObjectKeys.merchantObject("01MERCHANT", "01OBJECT", "application/pdf"))
                .isEqualTo("01MERCHANT/01OBJECT.pdf");
        assertThat(ObjectKeys.merchantObject("M", "O", "IMAGE/JPEG")).isEqualTo("M/O.jpg");
        assertThat(ObjectKeys.merchantObject("M", "O", "image/heic")).isEqualTo("M/O.heic");
        assertThat(ObjectKeys.merchantObject("M", "O", "application/octet-stream"))
                .isEqualTo("M/O");
    }

    @Test
    void idsThatWouldEscapeThePrefixAreRefused() {
        assertThatThrownBy(() -> ObjectKeys.merchantObject("..", "O", "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectKeys.merchantObject("a/b", "../O", "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void seedKeysOfTheDevDataAreValid() {
        assertThat(ObjectKeys.requireValid("seed/pho-dac-biet.jpg")).isEqualTo("seed/pho-dac-biet.jpg");
    }
}
