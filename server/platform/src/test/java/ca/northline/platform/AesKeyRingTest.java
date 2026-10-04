package ca.northline.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Engineering follow-ups (S-115 gap): versioned AES keys for TOTP_KEY and WEBHOOK_SECRET_KEY. Obviously fake keys. */
class AesKeyRingTest {

    static final String OLD = "b2xkLWZha2Uta2V5LWZvci10ZXN0cy0zMi1ieXRlcyE=";
    static final String NEW = "bmV3LWZha2Uta2V5LWZvci10ZXN0cy0zMi1ieXRlcyE=";

    @Test
    void theCurrentKeyEncrypts_previousKeysOnlyDecrypt_andTheOpeningKeyIsReported() {
        var before = AesKeyRing.of("TEST_KEY", "v1", OLD, null);
        var sealed = before.encrypt("secret");

        var after = AesKeyRing.of("TEST_KEY", "v2", NEW, "v1=" + OLD);
        var opened = after.decrypt(sealed, null);
        assertThat(opened.plain()).isEqualTo("secret");
        assertThat(opened.keyId()).isEqualTo("v1");
        assertThat(after.decrypt(after.encrypt("x"), "v1").keyId())
                .as("a stale hint still opens with the right key")
                .isEqualTo("v2");
        assertThat(after.keyIds()).containsExactly("v2", "v1");
        assertThatThrownBy(() -> AesKeyRing.of("TEST_KEY", "v2", NEW, null).decrypt(sealed, "v1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void valuesWrittenBeforeKeyIds_openUnchanged() {
        var legacy = WebhookSecretBox.of(OLD, true).orElseThrow();
        var stored = legacy.encrypt("whsec_x");
        assertThat(legacy.keyRef()).isEqualTo(WebhookSecretBox.KEY_REF);

        var rotated = WebhookSecretBox.of(NEW, "v2", "v1=" + OLD, false).orElseThrow();
        assertThat(rotated.keyRef()).isEqualTo("db:aes-gcm:v2");
        assertThat(rotated.decrypt(stored)).isEqualTo("whsec_x");
        assertThat(rotated.open(stored, WebhookSecretBox.KEY_REF).keyRef()).isEqualTo(WebhookSecretBox.KEY_REF);
    }

    @Test
    void misconfigurationIsRefusedWithTheVariablesName() {
        assertThatThrownBy(() -> AesKeyRing.of("TOTP_KEY", "v1", NEW, "v1=" + OLD))
                .hasMessageContaining("TOTP_KEY");
        assertThatThrownBy(() -> AesKeyRing.of("TOTP_KEY", "bad id!", NEW, null))
                .hasMessageContaining("key id");
        assertThatThrownBy(() -> AesKeyRing.of("TOTP_KEY", "v2", "c2hvcnQ=", null))
                .hasMessageContaining("32 bytes");
    }
}
