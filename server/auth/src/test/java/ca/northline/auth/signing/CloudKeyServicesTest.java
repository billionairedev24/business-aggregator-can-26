package ca.northline.auth.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.SignResult;
import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;
import com.azure.security.keyvault.keys.models.JsonWebKey;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.AsymmetricSignResponse;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.PublicKey;
import com.google.protobuf.ByteString;
import com.nimbusds.jose.jwk.JWK;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SignResponse;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

/**
 * The cloud adapters against SDK mocks that behave like the services: the "KMS" holds a P-256 key pair, signs the
 * digest it receives (DER for AWS and Google, {@code R || S} for Azure) and serves the public key. A token signed through
 * {@link KeyStoreJwtEncoder} must verify against the published JWK set, with the thumbprint as {@code kid}.
 */
class CloudKeyServicesTest {

    /** A key pair standing in for the key inside the service. */
    static final class FakeKmsKey {
        final KeyPair pair = generate();

        byte[] derSignatureOf(byte[] digest) {
            try {
                var signature = Signature.getInstance("NONEwithECDSA");
                signature.initSign(pair.getPrivate());
                signature.update(digest);
                return signature.sign();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        String pem() {
            return "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
        }

        private static KeyPair generate() {
            try {
                var generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                return generator.generateKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static void assertSignsVerifiableTokens(SigningKeys keys) throws Exception {
        var now = Instant.now();
        var jwt = new KeyStoreJwtEncoder(keys)
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256)
                                .type("JWT")
                                .build(),
                        JwtClaimsSet.builder()
                                .issuer("https://auth.northline.ca")
                                .subject("01J9ZD3V00000000000000RAV1")
                                .issuedAt(now)
                                .expiresAt(now.plus(Duration.ofMinutes(5)))
                                .claim("amr", List.of("hwk"))
                                .build()));
        var active = keys.published().getFirst();
        assertThat(jwt.getHeaders()).containsEntry("kid", active.getKeyID()).containsEntry("alg", "ES256");
        assertThat(active.getKeyID()).isEqualTo(active.computeThumbprint().toString());
        assertThat(active.isPrivate()).isFalse();
        var decoded = LocalFileSigningKeysTest.decode(keys, jwt.getTokenValue());
        assertThat(decoded.getSubject()).isEqualTo("01J9ZD3V00000000000000RAV1");
        assertThat(decoded.getClaimAsStringList("amr")).containsExactly("hwk");
    }

    @Nested
    class Aws {
        final FakeKmsKey active = new FakeKmsKey();
        final FakeKmsKey previous = new FakeKmsKey();
        final KmsClient kms = mock(KmsClient.class);

        Aws() {
            stub("arn:aws:kms:ca-central-1:111122223333:key/active", active);
            stub("arn:aws:kms:ca-central-1:111122223333:key/previous", previous);
        }

        private void stub(String arn, FakeKmsKey key) {
            when(kms.getPublicKey(GetPublicKeyRequest.builder().keyId(arn).build()))
                    .thenReturn(GetPublicKeyResponse.builder()
                            .keyId(arn)
                            .keySpec(KeySpec.ECC_NIST_P256)
                            .publicKey(
                                    SdkBytes.fromByteArray(key.pair.getPublic().getEncoded()))
                            .build());
            when(kms.sign(any(SignRequest.class))).thenAnswer(inv -> {
                SignRequest request = inv.getArgument(0);
                assertThat(request.messageType()).isEqualTo(MessageType.DIGEST);
                assertThat(request.signingAlgorithm()).isEqualTo(SigningAlgorithmSpec.ECDSA_SHA_256);
                var signer = request.keyId().endsWith("active") ? active : previous;
                return SignResponse.builder()
                        .signature(SdkBytes.fromByteArray(
                                signer.derSignatureOf(request.message().asByteArray())))
                        .build();
            });
        }

        @Test
        void signsWithTheActiveKey_andPublishesTheOthers() throws Exception {
            var keys = new RemoteSigningKeys(
                    new AwsKmsKeyService(kms),
                    "arn:aws:kms:ca-central-1:111122223333:key/active",
                    List.of("arn:aws:kms:ca-central-1:111122223333:key/previous"));

            assertSignsVerifiableTokens(keys);
            assertThat(keys.published()).hasSize(2);
            assertThat(keys.describe())
                    .extracting(SigningKeys.KeyState::status)
                    .containsExactly(SigningKeys.KeyState.Status.ACTIVE, SigningKeys.KeyState.Status.RETIRING);
        }

        @Test
        void refusesAKeyThatIsNotP256() {
            when(kms.getPublicKey(GetPublicKeyRequest.builder().keyId("rsa").build()))
                    .thenReturn(GetPublicKeyResponse.builder()
                            .keySpec(KeySpec.RSA_2048)
                            .build());
            assertThatThrownBy(() -> new RemoteSigningKeys(new AwsKmsKeyService(kms), "rsa", List.of()))
                    .hasMessageContaining("not ECC_NIST_P256");
        }
    }

    @Nested
    class Gcp {
        static final String VERSION =
                "projects/northline-prod/locations/northamerica-northeast1/keyRings/auth/cryptoKeys/token-signing/cryptoKeyVersions/3";
        final FakeKmsKey key = new FakeKmsKey();
        final KeyManagementServiceClient kms = mock(KeyManagementServiceClient.class);

        @Test
        void signsDigestsRemotely() throws Exception {
            when(kms.getPublicKey(VERSION))
                    .thenReturn(PublicKey.newBuilder()
                            .setPem(key.pem())
                            .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)
                            .build());
            when(kms.asymmetricSign(any(AsymmetricSignRequest.class))).thenAnswer(inv -> {
                AsymmetricSignRequest request = inv.getArgument(0);
                assertThat(request.getName()).isEqualTo(VERSION);
                var digest = request.getDigest().getSha256().toByteArray();
                return AsymmetricSignResponse.newBuilder()
                        .setSignature(ByteString.copyFrom(key.derSignatureOf(digest)))
                        .build();
            });

            assertSignsVerifiableTokens(new RemoteSigningKeys(new GcpKmsKeyService(kms), VERSION, List.of()));
        }

        @Test
        void refusesAnotherAlgorithm() {
            when(kms.getPublicKey(VERSION))
                    .thenReturn(PublicKey.newBuilder()
                            .setPem(key.pem())
                            .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_P384_SHA384)
                            .build());
            assertThatThrownBy(() -> new RemoteSigningKeys(new GcpKmsKeyService(kms), VERSION, List.of()))
                    .hasMessageContaining("not EC_SIGN_P256_SHA256");
        }
    }

    @Nested
    class Azure {
        static final String KEY = "https://northline-prod.vault.azure.net/keys/token-signing/4f1c2d";
        final FakeKmsKey key = new FakeKmsKey();
        final CryptographyClient client = mock(CryptographyClient.class);

        @Test
        void signsWithKeyVault_whichAnswersRawSignatures() throws Exception {
            var vaultKey = mock(KeyVaultKey.class);
            when(vaultKey.getKey()).thenReturn(JsonWebKey.fromEc(key.pair, Security.getProvider("SunEC")));
            when(client.getKey()).thenReturn(vaultKey);
            when(client.sign(eq(SignatureAlgorithm.ES256), any(byte[].class))).thenAnswer(inv -> {
                byte[] digest = inv.getArgument(1);
                var raw = RemoteSigningKeys.derToJws(key.derSignatureOf(digest));
                return new SignResult(raw, SignatureAlgorithm.ES256, KEY);
            });
            var built = new java.util.ArrayList<String>();
            var service = new AzureKeyVaultKeyService(id -> {
                built.add(id);
                return client;
            });

            var keys = new RemoteSigningKeys(service, KEY, List.of());
            assertSignsVerifiableTokens(keys);
            assertSignsVerifiableTokens(keys);
            assertThat(built).containsExactly(KEY); // one client per key, reused
            assertThat(keys.published()).extracting(JWK::getKeyID).hasSize(1);
        }

        @Test
        void refusesAnRsaKey() throws Exception {
            var rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            var vaultKey = mock(KeyVaultKey.class);
            when(vaultKey.getKey()).thenReturn(JsonWebKey.fromRsa(rsa));
            when(client.getKey()).thenReturn(vaultKey);
            var service = new AzureKeyVaultKeyService(anyKey -> client);
            assertThatThrownBy(() -> new RemoteSigningKeys(service, KEY, List.of()))
                    .hasMessageContaining("not an EC P-256 key");
        }
    }
}
