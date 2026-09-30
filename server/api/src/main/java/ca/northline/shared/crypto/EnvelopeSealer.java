package ca.northline.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope encryption: a fresh 256-bit data key per value, AES-GCM with the context as additional data, the data key
 * wrapped by the {@link KeyWrapper}. The plaintext data key is dropped as soon as the value is sealed or opened.
 */
final class EnvelopeSealer implements SecretSealer {

    private static final int KEY_BYTES = 32;
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyWrapper wrapper;

    EnvelopeSealer(KeyWrapper wrapper) {
        this.wrapper = wrapper;
    }

    String provider() {
        return wrapper.provider();
    }

    @Override
    public Sealed seal(String plaintext, String context) {
        var dataKey = new byte[KEY_BYTES];
        RANDOM.nextBytes(dataKey);
        try {
            var wrapped = wrapper.wrap(dataKey, context);
            return new Sealed(
                    wrapped.keyRef(),
                    wrapped.wrappedKey(),
                    aesGcmSeal(dataKey, plaintext.getBytes(StandardCharsets.UTF_8), context));
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public String open(Sealed sealed, String context) {
        var dataKey = wrapper.unwrap(sealed.keyRef(), sealed.wrappedKey(), context);
        try {
            return new String(aesGcmOpen(dataKey, sealed.ciphertext(), context), StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    static byte[] aesGcmSeal(byte[] key, byte[] plaintext, String context) {
        try {
            var nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            var sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    static byte[] aesGcmOpen(byte[] key, byte[] box, String context) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, box, 0, NONCE));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(box, NONCE, box.length - NONCE);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong key or context)", e);
        }
    }
}
