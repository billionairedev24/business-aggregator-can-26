package ca.northline.auth.persistence;

import ca.northline.auth.application.AuthProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for TOTP secrets at rest ({@code auth.totp_secrets.secret_enc} = 12-byte IV ‖ ciphertext+tag). The key
 * comes from {@code northline.auth.totp-key} (secrets manager in prod; a fixed dev key under {@code local}/{@code test}).
 */
@Component
class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    SecretCipher(AuthProperties props) {
        var raw = Base64.getDecoder().decode(props.totpKey());
        if (raw.length != 32) {
            throw new IllegalStateException("northline.auth.totp-key must be 32 bytes (base64)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    byte[] encrypt(String plain) {
        try {
            var iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    String decrypt(byte[] box) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, box, 0, IV_BYTES));
            return new String(cipher.doFinal(box, IV_BYTES, box.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong northline.auth.totp-key?)", e);
        }
    }
}
