package ca.northline.shared.crypto;

import ca.northline.shared.Conflict;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;

/** The {@link KeyWrapper}s: a local AES key, AWS KMS, Google Cloud KMS and Azure Key Vault. */
final class KeyWrappers {
    private KeyWrappers() {}

    /** Encryption context key sent to AWS KMS (visible in CloudTrail; holds a row id, never a secret). */
    static final String CONTEXT_KEY = "northline";

    /**
     * {@code KMS_PROVIDER=local}: AES-256-GCM with a 32-byte key from {@code KMS_LOCAL_KEY} (or the fixed development
     * key under {@code local}/{@code test}). The key reference is a fingerprint, so a value sealed under another local
     * key is refused with a clear message instead of a tag mismatch. Keys from {@code KMS_LOCAL_PREVIOUS_KEYS} only
     * unwrap (S-115: rotate by moving the old key there, let the re-wrap job finish, then drop it).
     */
    static final class Local implements KeyWrapper {
        private final byte[] key;
        private final String keyRef;
        private final Map<String, byte[]> previous;

        Local(byte[] key) {
            this(key, List.of());
        }

        Local(byte[] key, List<byte[]> previousKeys) {
            this.key = checked(key).clone();
            this.keyRef = ref(key);
            var old = new HashMap<String, byte[]>();
            for (var p : previousKeys) {
                old.put(ref(checked(p)), p.clone());
            }
            old.remove(keyRef);
            this.previous = Map.copyOf(old);
        }

        @Override
        public String provider() {
            return previous.isEmpty() ? "local" : "local (+" + previous.size() + " previous key(s) for unwrapping)";
        }

        @Override
        public Wrapped wrap(byte[] dataKey, String context) {
            return new Wrapped(keyRef, EnvelopeSealer.aesGcmSeal(key, dataKey, context));
        }

        @Override
        public byte[] unwrap(String ref, byte[] wrappedKey, String context) {
            if (keyRef.equals(ref)) {
                return EnvelopeSealer.aesGcmOpen(key, wrappedKey, context);
            }
            var old = previous.get(ref);
            if (old == null) {
                throw new IllegalStateException("Sealed with another key (" + ref + "), this api has " + keyRef
                        + (previous.isEmpty() ? "" : " and " + previous.keySet()));
            }
            return EnvelopeSealer.aesGcmOpen(old, wrappedKey, context);
        }

        private static byte[] checked(byte[] key) {
            if (key.length != 32) {
                throw new IllegalStateException("KMS_LOCAL_KEY must be 32 bytes (base64)");
            }
            return key;
        }

        private static String ref(byte[] key) {
            return "local:" + fingerprint(key);
        }

        private static String fingerprint(byte[] key) {
            try {
                return HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(key))
                        .substring(0, 16);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** {@code local} outside local/test without {@code KMS_LOCAL_KEY}: every use answers 409. */
    static final class Unavailable implements KeyWrapper {
        @Override
        public String provider() {
            return "local (no KMS_LOCAL_KEY)";
        }

        @Override
        public Wrapped wrap(byte[] dataKey, String context) {
            throw unavailable();
        }

        @Override
        public byte[] unwrap(String keyRef, byte[] wrappedKey, String context) {
            throw unavailable();
        }

        private static Conflict unavailable() {
            return new Conflict("encryption_unavailable", "Secrets can't be stored here yet (no encryption key).");
        }
    }

    /** AWS KMS symmetric key: Encrypt / Decrypt with the context as encryption context. keyRef = the key ARN. */
    static final class Aws implements KeyWrapper {
        private final KmsClient kms;
        private final String keyId;

        Aws(KmsClient kms, String keyId) {
            this.kms = kms;
            this.keyId = keyId;
        }

        @Override
        public String provider() {
            return "aws";
        }

        @Override
        public Wrapped wrap(byte[] dataKey, String context) {
            var r = kms.encrypt(EncryptRequest.builder()
                    .keyId(keyId)
                    .plaintext(SdkBytes.fromByteArray(dataKey))
                    .encryptionContext(Map.of(CONTEXT_KEY, context))
                    .build());
            return new Wrapped(r.keyId(), r.ciphertextBlob().asByteArray());
        }

        @Override
        public byte[] unwrap(String keyRef, byte[] wrappedKey, String context) {
            return kms.decrypt(DecryptRequest.builder()
                            .keyId(keyRef)
                            .ciphertextBlob(SdkBytes.fromByteArray(wrappedKey))
                            .encryptionContext(Map.of(CONTEXT_KEY, context))
                            .build())
                    .plaintext()
                    .asByteArray();
        }
    }

    /**
     * Google Cloud KMS symmetric key ({@code GOOGLE_SYMMETRIC_ENCRYPTION}): encrypt / decrypt with the context as
     * additional authenticated data. keyRef = the key <b>version</b> that encrypted (the response's name, S-115), so a
     * value wrapped before the primary version changed is found and re-wrapped; decrypt is called on the crypto key
     * (the ciphertext names its version itself). Values stored before S-115 carry the key name — they open the same.
     */
    static final class Gcp implements KeyWrapper {
        private final KeyManagementServiceClient kms;
        private final String keyName;

        Gcp(KeyManagementServiceClient kms, String keyName) {
            this.kms = kms;
            this.keyName = cryptoKey(keyName);
        }

        @Override
        public String provider() {
            return "gcp";
        }

        @Override
        public Wrapped wrap(byte[] dataKey, String context) {
            var r = kms.encrypt(com.google.cloud.kms.v1.EncryptRequest.newBuilder()
                    .setName(keyName)
                    .setPlaintext(ByteString.copyFrom(dataKey))
                    .setAdditionalAuthenticatedData(ByteString.copyFrom(context, StandardCharsets.UTF_8))
                    .build());
            return new Wrapped(
                    r.getName().isEmpty() ? keyName : r.getName(),
                    r.getCiphertext().toByteArray());
        }

        @Override
        public byte[] unwrap(String keyRef, byte[] wrappedKey, String context) {
            return kms.decrypt(com.google.cloud.kms.v1.DecryptRequest.newBuilder()
                            .setName(cryptoKey(keyRef))
                            .setCiphertext(ByteString.copyFrom(wrappedKey))
                            .setAdditionalAuthenticatedData(ByteString.copyFrom(context, StandardCharsets.UTF_8))
                            .build())
                    .getPlaintext()
                    .toByteArray();
        }

        private static String cryptoKey(String name) {
            var version = name.indexOf("/cryptoKeyVersions/");
            return version < 0 ? name : name.substring(0, version);
        }
    }

    /**
     * Azure Key Vault RSA key: wrapKey / unwrapKey with RSA-OAEP-256. keyRef = the versioned key URL the vault
     * answered with, so values wrapped before a rotation still open. RSA-OAEP has no additional data; the context is
     * still bound by the AES-GCM layer.
     */
    static final class Azure implements KeyWrapper {
        private final Function<String, CryptographyClient> clients;
        private final String keyId;
        private final Map<String, CryptographyClient> cache = new ConcurrentHashMap<>();

        Azure(Function<String, CryptographyClient> clients, String keyId) {
            this.clients = clients;
            this.keyId = keyId;
        }

        @Override
        public String provider() {
            return "azure";
        }

        @Override
        public Wrapped wrap(byte[] dataKey, String context) {
            var r = client(keyId).wrapKey(KeyWrapAlgorithm.RSA_OAEP_256, dataKey);
            return new Wrapped(r.getKeyId() == null ? keyId : r.getKeyId(), r.getEncryptedKey());
        }

        @Override
        public byte[] unwrap(String keyRef, byte[] wrappedKey, String context) {
            return client(keyRef)
                    .unwrapKey(KeyWrapAlgorithm.RSA_OAEP_256, wrappedKey)
                    .getKey();
        }

        private CryptographyClient client(String id) {
            return cache.computeIfAbsent(id, clients);
        }
    }
}
