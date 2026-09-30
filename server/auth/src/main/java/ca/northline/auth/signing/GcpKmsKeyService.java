package ca.northline.auth.signing;

import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.Digest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.RequiredArgsConstructor;

/**
 * Google Cloud KMS: a key version with algorithm {@code EC_SIGN_P256_SHA256} (purpose {@code ASYMMETRIC_SIGN}).
 * Key id = the key <em>version</em> resource name
 * ({@code projects/…/locations/northamerica-northeast1/keyRings/…/cryptoKeys/…/cryptoKeyVersions/N}); rotating means a
 * new version. Credentials: Application Default Credentials (Workload Identity in the cluster).
 */
@RequiredArgsConstructor
final class GcpKmsKeyService implements KeyService {

    private final KeyManagementServiceClient kms;

    @Override
    public String name() {
        return "gcp";
    }

    @Override
    public ECPublicKey publicKey(String keyId) {
        var response = kms.getPublicKey(keyId);
        if (response.getAlgorithm() != CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256) {
            throw new IllegalStateException(
                    "Cloud KMS key " + keyId + " is " + response.getAlgorithm() + ", not EC_SIGN_P256_SHA256");
        }
        var der = Base64.getMimeDecoder()
                .decode(response.getPem()
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .strip());
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cloud KMS key " + keyId + " has an unreadable public key", e);
        }
    }

    @Override
    public byte[] signDigest(String keyId, byte[] sha256) {
        var response = kms.asymmetricSign(AsymmetricSignRequest.newBuilder()
                .setName(keyId)
                .setDigest(Digest.newBuilder().setSha256(ByteString.copyFrom(sha256)))
                .build());
        return RemoteSigningKeys.derToJws(response.getSignature().toByteArray());
    }
}
