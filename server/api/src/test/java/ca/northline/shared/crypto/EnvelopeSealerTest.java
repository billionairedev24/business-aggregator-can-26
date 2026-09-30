package ca.northline.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.northline.shared.Conflict;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.UnwrapResult;
import com.azure.security.keyvault.keys.cryptography.models.WrapResult;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Envelope encryption (S-32): the local key, and the Cloud KMS / Key Vault adapters over SDK mocks. */
class EnvelopeSealerTest {

    static final byte[] KEY = Base64.getDecoder().decode(CryptoConfiguration.DEV_KEY);

    @Nested
    class Local {

        final EnvelopeSealer sealer = new EnvelopeSealer(new KeyWrappers.Local(KEY));

        @Test
        void roundTrip_withAFreshDataKeyEachTime() {
            var a = sealer.seal("1//fake-refresh-token", "link-1");
            var b = sealer.seal("1//fake-refresh-token", "link-1");
            assertThat(sealer.open(a, "link-1")).isEqualTo("1//fake-refresh-token");
            assertThat(a.keyRef()).startsWith("local:").isEqualTo(b.keyRef());
            assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
            assertThat(a.wrappedKey()).isNotEqualTo(b.wrappedKey());
            assertThat(new String(a.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("fake-refresh");
        }

        @Test
        void aValueSealedForOneRow_cantBeOpenedForAnother() {
            var sealed = sealer.seal("secret", "link-1");
            assertThatThrownBy(() -> sealer.open(sealed, "link-2")).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void anotherLocalKey_isNamedInTheError() {
            var other = new EnvelopeSealer(new KeyWrappers.Local(new byte[32]));
            var sealed = sealer.seal("secret", "link-1");
            assertThatThrownBy(() -> other.open(sealed, "link-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Sealed with another key");
            assertThatThrownBy(() -> new KeyWrappers.Local(new byte[16])).hasMessageContaining("32 bytes");
        }

        @Test
        void withoutAKey_nothingCanBeSealed() {
            var none = new EnvelopeSealer(new KeyWrappers.Unavailable());
            assertThatThrownBy(() -> none.seal("secret", "x"))
                    .isInstanceOf(Conflict.class)
                    .hasFieldOrPropertyWithValue("code", "encryption_unavailable");
        }
    }

    /** Cloud KMS "encrypts" by reversing the bytes, and checks the name and the additional data like the service. */
    @Test
    void gcp_wrapsWithTheCryptoKey_andBindsTheContext() {
        var kms = mock(KeyManagementServiceClient.class);
        var name = "projects/p/locations/northamerica-northeast1/keyRings/northline-dev/cryptoKeys/tokens";
        when(kms.encrypt(any(EncryptRequest.class))).thenAnswer(i -> {
            EncryptRequest r = i.getArgument(0);
            assertThat(r.getName()).isEqualTo(name);
            return EncryptResponse.newBuilder()
                    .setName(name + "/cryptoKeyVersions/3")
                    .setCiphertext(reverse(r.getPlaintext().concat(r.getAdditionalAuthenticatedData())))
                    .build();
        });
        when(kms.decrypt(any(DecryptRequest.class))).thenAnswer(i -> {
            DecryptRequest r = i.getArgument(0);
            var plain = reverse(r.getCiphertext());
            var aad = r.getAdditionalAuthenticatedData();
            if (!plain.endsWith(aad)) {
                throw new IllegalStateException("INVALID_ARGUMENT: Decryption failed");
            }
            return DecryptResponse.newBuilder()
                    .setPlaintext(plain.substring(0, plain.size() - aad.size()))
                    .build();
        });
        // a key *version* name is accepted too: encrypt needs the key
        var sealer = new EnvelopeSealer(new KeyWrappers.Gcp(kms, name + "/cryptoKeyVersions/1"));

        var sealed = sealer.seal("M.fake-refresh", "link-9");
        assertThat(sealed.keyRef()).isEqualTo(name);
        assertThat(sealer.open(sealed, "link-9")).isEqualTo("M.fake-refresh");
        assertThatThrownBy(() -> sealer.open(sealed, "link-8")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void azure_wrapsWithRsaOaep256_andOpensWithTheVersionThatWrapped() {
        var current = "https://nl-dev-kms-abc123.vault.azure.net/keys/tokens/v2";
        var previous = "https://nl-dev-kms-abc123.vault.azure.net/keys/tokens/v1";
        var used = new AtomicReference<String>();
        var client = mock(CryptographyClient.class);
        when(client.wrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class)))
                .thenAnswer(i ->
                        new WrapResult(reverse((byte[]) i.getArgument(1)), KeyWrapAlgorithm.RSA_OAEP_256, current));
        when(client.unwrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class)))
                .thenAnswer(i -> new UnwrapResult(
                        reverse((byte[]) i.getArgument(1)), KeyWrapAlgorithm.RSA_OAEP_256, used.get()));
        var sealer = new EnvelopeSealer(new KeyWrappers.Azure(
                id -> {
                    used.set(id);
                    return client;
                },
                previous));

        var sealed = sealer.seal("M.fake-refresh", "link-3");
        assertThat(sealed.keyRef()).as("the version the vault answered with").isEqualTo(current);
        assertThat(sealer.open(sealed, "link-3")).isEqualTo("M.fake-refresh");
        assertThat(used.get()).isEqualTo(current);
    }

    private static ByteString reverse(ByteString s) {
        return ByteString.copyFrom(reverse(s.toByteArray()));
    }

    private static byte[] reverse(byte[] b) {
        var out = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = b[b.length - 1 - i];
        }
        return out;
    }
}
