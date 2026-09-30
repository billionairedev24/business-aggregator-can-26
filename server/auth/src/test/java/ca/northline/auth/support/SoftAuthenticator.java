package ca.northline.auth.support;

import com.jayway.jsonpath.JsonPath;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * A software passkey for tests: answers the options the auth server hands the browser the way
 * {@code navigator.credentials.create/get} would (packed "none" attestation, ES256), and produces the JSON the Studio
 * posts back.
 */
public final class SoftAuthenticator {

    private static final byte UP = 0x01;
    private static final byte UV = 0x04;
    private static final byte AT = 0x40;

    private String origin;
    private final KeyPair keys;
    private final byte[] credentialId = new byte[16];
    private byte[] userHandle = new byte[0];
    private int counter;
    private boolean userVerification = true;
    private boolean counting = true;

    public SoftAuthenticator(String origin) throws Exception {
        this.origin = origin;
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        this.keys = generator.generateKeyPair();
        new SecureRandom().nextBytes(credentialId);
    }

    /** S-20: an authenticator that only checks presence (no PIN / biometric) — the UV flag stays off. */
    public SoftAuthenticator withoutUserVerification() {
        userVerification = false;
        return this;
    }

    /** S-20: the same credential used from another web origin (a phishing page on another host). */
    public SoftAuthenticator fromOrigin(String otherOrigin) {
        origin = otherOrigin;
        return this;
    }

    /** An authenticator that doesn't count (synced passkeys): every assertion carries counter 0. */
    public SoftAuthenticator notCounting() {
        counting = false;
        return this;
    }

    /** S-20: a clone of this authenticator that is behind: the next assertion carries {@code value + 1}. */
    public SoftAuthenticator counterAt(int value) {
        counter = value;
        return this;
    }

    public String credentialId() {
        return b64(credentialId);
    }

    /** navigator.credentials.create() for creation-options JSON → the credential JSON. */
    public String create(String optionsJson) throws Exception {
        String rpId = JsonPath.read(optionsJson, "$.rp.id");
        String challenge = JsonPath.read(optionsJson, "$.challenge");
        userHandle = Base64.getUrlDecoder().decode(JsonPath.<String>read(optionsJson, "$.user.id"));
        var clientData = clientData("webauthn.create", challenge);
        var cose = EC2COSEKey.create(keys, COSEAlgorithmIdentifier.ES256);
        var authData = new AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
                sha256(rpId.getBytes(StandardCharsets.UTF_8)),
                (byte) (UP | (userVerification ? UV : 0) | AT),
                0L,
                new AttestedCredentialData(AAGUID.ZERO, credentialId, cose));
        var attestation = new AttestationObjectConverter(new ObjectConverter())
                .convertToBytes(new AttestationObject(authData, new NoneAttestationStatement()));
        return """
                {"id":"%1$s","rawId":"%1$s","type":"public-key","authenticatorAttachment":"platform",\
                "clientExtensionResults":{},"response":{"attestationObject":"%2$s","clientDataJSON":"%3$s",\
                "transports":["internal"]}}""".formatted(credentialId(), b64(attestation), b64(clientData));
    }

    /** navigator.credentials.get() for request-options JSON → the assertion JSON. */
    public String get(String optionsJson) throws Exception {
        return get(optionsJson, false);
    }

    /** As {@link #get(String)}, optionally with a corrupted signature. */
    public String get(String optionsJson, boolean tamper) throws Exception {
        String rpId = JsonPath.read(optionsJson, "$.rpId");
        String challenge = JsonPath.read(optionsJson, "$.challenge");
        var clientData = clientData("webauthn.get", challenge);
        var authData = ByteBuffer.allocate(37)
                .put(sha256(rpId.getBytes(StandardCharsets.UTF_8)))
                .put((byte) (UP | (userVerification ? UV : 0)))
                .putInt(counting ? ++counter : 0)
                .array();
        var signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(authData);
        signer.update(sha256(clientData));
        var signature = signer.sign();
        if (tamper) {
            signature[signature.length - 1] ^= 0x01;
        }
        return """
                {"id":"%1$s","rawId":"%1$s","type":"public-key","authenticatorAttachment":"platform",\
                "clientExtensionResults":{},"response":{"authenticatorData":"%2$s","clientDataJSON":"%3$s",\
                "signature":"%4$s","userHandle":"%5$s"}}""".formatted(credentialId(), b64(authData), b64(clientData), b64(signature), b64(userHandle));
    }

    private byte[] clientData(String type, String challenge) {
        return """
                {"type":"%s","challenge":"%s","origin":"%s","crossOrigin":false}""".formatted(type, challenge, origin).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
