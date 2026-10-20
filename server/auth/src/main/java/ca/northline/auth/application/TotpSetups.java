package ca.northline.auth.application;

import ca.northline.auth.domain.Totp;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.util.Utils;

/**
 * An authenticator-app set-up (RFC 6238, SHA-1, 6 digits, 30 s): a fresh secret, its {@code otpauth://} URI (issuer
 * {@code northline.auth.totp-issuer}, account = the person's email) and the same URI as a PNG QR code. Shared by
 * registration, enrolment after sign-up (S-154) and Settings › Security.
 */
final class TotpSetups {

    private static final DefaultSecretGenerator SECRETS = new DefaultSecretGenerator(32);

    private TotpSetups() {}

    static String newSecret() {
        return SECRETS.generate();
    }

    static RegistrationService.TotpSetup of(String secret, String account, String issuer) {
        var data = new QrData.Builder()
                .label(issuer + ":" + account) // Key URI Format: "Issuer:account" plus the issuer parameter
                .secret(secret)
                .issuer(issuer)
                .digits(Totp.DIGITS)
                .period(Totp.PERIOD_SECONDS)
                .build();
        try {
            var png = new ZxingPngQrGenerator();
            return new RegistrationService.TotpSetup(
                    secret, data.getUri(), Utils.getDataUriForImage(png.generate(data), png.getImageMimeType()));
        } catch (QrGenerationException e) {
            throw new IllegalStateException("QR generation failed", e);
        }
    }
}
