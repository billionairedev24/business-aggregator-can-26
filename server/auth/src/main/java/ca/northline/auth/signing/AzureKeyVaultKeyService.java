package ca.northline.auth.signing;

import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyType;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import java.security.interfaces.ECPublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Azure Key Vault (or Managed HSM): an {@code EC} / {@code EC-HSM} key on curve {@code P-256}; {@code sign} with
 * {@code ES256} (Key Vault already returns {@code R || S}), {@code get} for the public key. Key id = the key identifier
 * URL <em>with</em> its version ({@code https://<vault>.vault.azure.net/keys/<name>/<version>}), so a new version
 * never starts signing before it is published. Credentials: {@code DefaultAzureCredential} (workload identity).
 */
final class AzureKeyVaultKeyService implements KeyService {

    private final Function<String, CryptographyClient> clients;
    private final Map<String, CryptographyClient> byKey = new ConcurrentHashMap<>();

    /** @param clients builds the client of one key identifier */
    AzureKeyVaultKeyService(Function<String, CryptographyClient> clients) {
        this.clients = clients;
    }

    @Override
    public String name() {
        return "azure";
    }

    @Override
    public ECPublicKey publicKey(String keyId) {
        var key = client(keyId).getKey().getKey();
        if (!(KeyType.EC.equals(key.getKeyType()) || KeyType.EC_HSM.equals(key.getKeyType()))
                || !KeyCurveName.P_256.equals(key.getCurveName())) {
            throw new IllegalStateException("Key Vault key " + keyId + " is not an EC P-256 key");
        }
        try {
            return new ECKey.Builder(Curve.P_256, Base64URL.encode(key.getX()), Base64URL.encode(key.getY()))
                    .build()
                    .toECPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException("Key Vault key " + keyId + " has an unreadable public key", e);
        }
    }

    @Override
    public byte[] signDigest(String keyId, byte[] sha256) {
        return client(keyId).sign(SignatureAlgorithm.ES256, sha256).getSignature();
    }

    private CryptographyClient client(String keyId) {
        return byKey.computeIfAbsent(keyId, clients);
    }
}
