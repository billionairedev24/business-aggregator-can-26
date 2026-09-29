package ca.northline.auth.application;

import ca.northline.auth.domain.AuthMessages;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Passkeys through Spring Security's WebAuthn support ({@link WebAuthnRelyingPartyOperations} with the JDBC
 * repositories on {@code auth.user_entities} / {@code auth.user_credentials}). The Studio drives it through the JSON API
 * instead of Spring's default {@code /webauthn/**} pages, because the Studio renders its own sign-in UI.
 *
 * <p>The WebAuthn user name is the {@code identity.users} id; the browser is shown the email and full name instead
 * ({@link #PRESENTED_USER}).
 */
@Slf4j
@Service
public class PasskeyService {

    /** The user entity the authenticator shows, bound for the duration of one options call. */
    public static final ScopedValue<PublicKeyCredentialUserEntity> PRESENTED_USER = ScopedValue.newInstance();

    private static final TypeReference<PublicKeyCredential<AuthenticatorAttestationResponse>> ATTESTATION =
            new TypeReference<>() {};
    private static final TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>> ASSERTION =
            new TypeReference<>() {};

    private final WebAuthnRelyingPartyOperations relyingParty;
    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final JsonMapper json =
            JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();

    public PasskeyService(
            WebAuthnRelyingPartyOperations relyingParty, PublicKeyCredentialUserEntityRepository userEntities) {
        this.relyingParty = relyingParty;
        this.userEntities = userEntities;
    }

    /** Options for {@code navigator.credentials.create()}; the user entity is created on first use. */
    public PublicKeyCredentialCreationOptions creationOptions(String userId, String accountName, String displayName) {
        var entity = userEntities.findByUsername(userId);
        if (entity == null) {
            entity = ImmutablePublicKeyCredentialUserEntity.builder()
                    .id(Bytes.random())
                    .name(userId)
                    .displayName(displayName)
                    .build();
            userEntities.save(entity);
        }
        var presented = ImmutablePublicKeyCredentialUserEntity.builder()
                .id(entity.getId())
                .name(accountName)
                .displayName(displayName)
                .build();
        Authentication owner = UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of());
        return ScopedValue.where(PRESENTED_USER, presented)
                .call(() -> relyingParty.createPublicKeyCredentialCreationOptions(
                        new ImmutablePublicKeyCredentialCreationOptionsRequest(owner)));
    }

    /** Verifies the attestation from the browser and stores the credential. */
    public void register(PublicKeyCredentialCreationOptions options, String credentialJson, String label) {
        try {
            var credential = json.readValue(credentialJson, ATTESTATION);
            relyingParty.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                    options, new RelyingPartyPublicKey(credential, label)));
        } catch (RuntimeException e) {
            log.info("Passkey registration rejected: {}", e.getMessage());
            throw InvalidInput.of("credential", "passkey", AuthMessages.PASSKEY_FAILED);
        }
    }

    /**
     * Options for {@code navigator.credentials.get()}: the user's credentials when known, otherwise empty
     * (discoverable passkeys — the authenticator offers what it has).
     */
    public PublicKeyCredentialRequestOptions requestOptions(@Nullable String userId) {
        Authentication who = userId == null
                ? new AnonymousAuthenticationToken(
                        "passkey", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
                : UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of());
        return relyingParty.createCredentialRequestOptions(new ImmutablePublicKeyCredentialRequestOptionsRequest(who));
    }

    /** Verifies an assertion; returns the {@code identity.users} id it belongs to. */
    public String authenticate(PublicKeyCredentialRequestOptions options, String assertionJson) {
        try {
            var credential = json.readValue(assertionJson, ASSERTION);
            return relyingParty
                    .authenticate(new RelyingPartyAuthenticationRequest(options, credential))
                    .getName();
        } catch (RuntimeException e) {
            log.info("Passkey assertion rejected: {}", e.getMessage());
            throw InvalidInput.of("credential", "passkey", AuthMessages.PASSKEY_FAILED);
        }
    }

    /** Options as the JSON the browser expects (base64url bytes, per WebAuthn Level 3 {@code toJSON()}). */
    public String toJson(Object options) {
        return json.writeValueAsString(options);
    }
}
