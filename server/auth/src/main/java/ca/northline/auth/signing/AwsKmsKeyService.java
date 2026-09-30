package ca.northline.auth.signing;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

/**
 * AWS KMS: an asymmetric {@code ECC_NIST_P256} key with usage {@code SIGN_VERIFY}; {@code kms:Sign} with
 * {@code ECDSA_SHA_256} over the digest, {@code kms:GetPublicKey} for the JWK set. Key id = key ARN, key id or alias
 * ARN. Credentials and region come from the SDK's default chain (workload identity in the cluster).
 */
@RequiredArgsConstructor
final class AwsKmsKeyService implements KeyService {

    private final KmsClient kms;

    @Override
    public String name() {
        return "aws";
    }

    @Override
    public ECPublicKey publicKey(String keyId) {
        var response =
                kms.getPublicKey(GetPublicKeyRequest.builder().keyId(keyId).build());
        if (response.keySpec() != KeySpec.ECC_NIST_P256) {
            throw new IllegalStateException("KMS key " + keyId + " is " + response.keySpec() + ", not ECC_NIST_P256");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(response.publicKey().asByteArray()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("KMS key " + keyId + " has an unreadable public key", e);
        }
    }

    @Override
    public byte[] signDigest(String keyId, byte[] sha256) {
        var response = kms.sign(SignRequest.builder()
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(sha256))
                .messageType(MessageType.DIGEST)
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
                .build());
        return RemoteSigningKeys.derToJws(response.signature().asByteArray());
    }
}
