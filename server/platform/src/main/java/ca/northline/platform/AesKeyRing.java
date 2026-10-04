package ca.northline.platform;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * AES-256-GCM under a <em>versioned</em> key (engineering follow-ups, S-115 gap): the <b>current</b> key, named by its
 * id ({@code v2}), encrypts; <b>previous</b> keys only decrypt, so a key can be rotated while the stored values are
 * re-encrypted by a job, the way the KMS re-wrap job moves data keys ({@code KMS_LOCAL_PREVIOUS_KEYS}). A stored value
 * is {@code nonce(12) ‖ ciphertext ‖ tag(16)} — the layout written before key ids existed, so old values open
 * unchanged; the key id lives next to the value (a {@code key_id} / {@code secret_ref} column). Decryption tries the
 * key the row names first, then the others: GCM's tag rejects a wrong key, so a row whose reference is missing or
 * stale still opens.
 *
 * <p>Previous keys are configured as {@code id=base64,id=base64}.
 */
public final class AesKeyRing {

    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String currentId;
    private final Map<String, SecretKeySpec> keys; // current first

    private AesKeyRing(String currentId, Map<String, SecretKeySpec> keys) {
        this.currentId = currentId;
        this.keys = keys;
    }

    /** A value (UTF-8 text) and the key that opened it. */
    public record Opened(String plain, String keyId) {}

    /**
     * @param variable the setting's name, for the error messages ({@code TOTP_KEY})
     * @param currentKey base64 of 32 bytes
     * @param previousKeys {@code id=base64,…}, blank for none
     */
    public static AesKeyRing of(String variable, String currentId, String currentKey, @Nullable String previousKeys) {
        var keys = new LinkedHashMap<String, SecretKeySpec>();
        keys.put(checkedId(variable, currentId), key(variable, currentKey));
        for (var entry : parsePrevious(variable, previousKeys).entrySet()) {
            if (keys.containsKey(entry.getKey())) {
                throw new IllegalArgumentException(variable + ": key id " + entry.getKey()
                        + " is both the current key and a previous one; give the new key a new id");
            }
            keys.put(entry.getKey(), entry.getValue());
        }
        return new AesKeyRing(currentId, keys);
    }

    private static Map<String, SecretKeySpec> parsePrevious(String variable, @Nullable String spec) {
        var keys = new LinkedHashMap<String, SecretKeySpec>();
        if (spec == null || spec.isBlank()) {
            return keys;
        }
        for (var part : spec.split(",")) {
            var item = part.strip();
            if (item.isEmpty()) {
                continue;
            }
            var eq = item.indexOf('=');
            var id = eq <= 0 ? "" : item.substring(0, eq).strip();
            if (!KEY_ID.matcher(id).matches()) {
                // never echo the entry: it may be a key
                throw new IllegalArgumentException(variable
                        + " previous keys must be id=base64 pairs separated by commas (an entry has no valid id)");
            }
            keys.put(id, key(variable, item.substring(eq + 1).strip()));
        }
        return keys;
    }

    private static String checkedId(String variable, String id) {
        if (!KEY_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(variable + ": a key id must be 1–32 of A-Z a-z 0-9 . _ -");
        }
        return id;
    }

    private static SecretKeySpec key(String variable, String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(variable + " must be base64 of 32 bytes", e);
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException(variable + " must be base64 of 32 bytes");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /** The id of the key that encrypts. */
    public String currentId() {
        return currentId;
    }

    /** Every key id, the current one first. */
    public List<String> keyIds() {
        return List.copyOf(keys.keySet());
    }

    /** Encrypts UTF-8 text with the current key. */
    public byte[] encrypt(String plain) {
        try {
            var nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    Objects.requireNonNull(keys.get(currentId)),
                    new GCMParameterSpec(TAG_BITS, nonce));
            var sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(NONCE + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Opens {@code blob} with the key {@code hint} names (when known), else with whichever key's tag matches.
     *
     * @throws IllegalStateException when no key opens it (a key missing from the ring, or a damaged value)
     */
    public Opened decrypt(byte[] blob, @Nullable String hint) {
        if (blob.length <= NONCE) {
            throw new IllegalStateException("Decryption failed: value too short");
        }
        var order = new ArrayList<String>();
        if (hint != null && keys.containsKey(hint)) {
            order.add(hint);
        }
        keys.keySet().stream().filter(id -> !id.equals(hint)).forEach(order::add);
        for (var id : order) {
            try {
                var cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        Objects.requireNonNull(keys.get(id)),
                        new GCMParameterSpec(TAG_BITS, Arrays.copyOf(blob, NONCE)));
                return new Opened(
                        new String(cipher.doFinal(blob, NONCE, blob.length - NONCE), StandardCharsets.UTF_8), id);
            } catch (GeneralSecurityException e) {
                // not this key: try the next one
            }
        }
        throw new IllegalStateException("Decryption failed with every key " + keys.keySet() + " (a key missing?)");
    }
}
