package ca.northline.auth.signing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.impl.ECDSA;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Cloud providers: the key service signs; northline-auth only holds public keys. The active key is
 * {@code KMS_KEY_ID}; {@code KMS_PUBLISHED_KEY_IDS} are published too (the next key before a rotation, the previous
 * one after it). Public keys are fetched once at start-up, so a wrong key id or missing permission stops the app
 * instead of failing the first sign-in. Rotation = a new key (version) in the key service and a configuration change —
 * see {@code docs/runbooks/key-rotation.md}.
 */
@Slf4j
final class RemoteSigningKeys implements SigningKeys {

    private final KeyService service;
    private final RemoteKey active;
    private final List<ECKey> published;

    RemoteSigningKeys(KeyService service, String activeKeyId, List<String> publishedKeyIds) {
        this.service = service;
        this.active = new RemoteKey(service, activeKeyId, jwk(service.publicKey(activeKeyId)));
        var byKid = new LinkedHashMap<String, ECKey>();
        byKid.put(active.keyId(), active.jwk());
        for (var id : publishedKeyIds) {
            if (!id.equals(activeKeyId)) {
                var key = jwk(service.publicKey(id));
                byKid.putIfAbsent(key.getKeyID(), key);
            }
        }
        this.published = List.copyOf(byKid.values());
        log.info(
                "Signing keys: provider={} active={} ({}) published={}",
                service.name(),
                active.keyId(),
                activeKeyId,
                byKid.keySet());
    }

    @Override
    public SigningKey active() {
        return active;
    }

    @Override
    public List<ECKey> published() {
        return published;
    }

    @Override
    public List<KeyState> describe() {
        var states = new ArrayList<KeyState>();
        states.add(new KeyState(active.keyId(), KeyState.Status.ACTIVE, service.name() + " " + active.resource()));
        published.stream()
                .skip(1)
                .forEach(k -> states.add(new KeyState(k.getKeyID(), KeyState.Status.RETIRING, "published only")));
        return List.copyOf(states);
    }

    /** Public JWK with the RFC 7638 thumbprint as {@code kid}. */
    static ECKey jwk(ECPublicKey publicKey) {
        try {
            return new ECKey.Builder(Curve.P_256, publicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Not an EC P-256 public key", e);
        }
    }

    /** DER {@code ECDSA-Sig-Value} (what AWS and Google return) → JWS {@code R || S}. */
    static byte[] derToJws(byte[] der) {
        try {
            return ECDSA.transcodeSignatureToConcat(der, ECDSA.getSignatureByteArrayLength(JWSAlgorithm.ES256));
        } catch (JOSEException e) {
            throw new IllegalStateException("Unexpected ECDSA signature from the key service", e);
        }
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A key held by the key service. */
    private record RemoteKey(KeyService service, String resource, ECKey jwk) implements SigningKey {

        @Override
        public String keyId() {
            return jwk.getKeyID();
        }

        @Override
        public byte[] sign(byte[] signingInput) {
            return service.signDigest(resource, sha256(signingInput));
        }
    }
}
