package ca.northline.auth.config;

import ca.northline.auth.application.AuthProperties;
import ca.northline.auth.application.PasskeyService;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

/**
 * Passkeys: Spring Security's WebAuthn relying party on the JDBC tables from V017 ({@code auth.user_*}). Origins and the
 * RP id come from configuration ({@code STUDIO_ORIGIN}, {@code CONSUMER_ORIGIN}, {@code WEBAUTHN_RP_ID}); user
 * verification (PIN / biometric) is required at registration and at every assertion (S-20), because a passkey alone
 * signs in with {@code acr=mfa}.
 */
@Configuration(proxyBeanMethods = false)
class WebAuthnConfig {

    @Bean
    PublicKeyCredentialUserEntityRepository passkeyUsers(JdbcOperations jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    UserCredentialRepository passkeyCredentials(JdbcOperations jdbc) {
        return new JdbcUserCredentialRepository(jdbc);
    }

    @Bean
    WebAuthnRelyingPartyOperations relyingParty(
            PublicKeyCredentialUserEntityRepository users, UserCredentialRepository credentials, AuthProperties props) {
        var rp = PublicKeyCredentialRpEntity.builder()
                .id(props.webauthn().rpId())
                .name(props.webauthn().rpName())
                .build();
        var ops = new Webauthn4JRelyingPartyOperations(
                users, credentials, rp, Set.copyOf(props.webauthn().origins()));
        ops.setCustomizeCreationOptions(options -> {
            // Discoverable credential so the "Passkey" button works without typing an email first.
            options.authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                    .residentKey(ResidentKeyRequirement.REQUIRED)
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .build());
            if (PasskeyService.PRESENTED_USER.isBound()) {
                options.user(PasskeyService.PRESENTED_USER.get());
            }
        });
        ops.setCustomizeRequestOptions(options -> options.userVerification(UserVerificationRequirement.REQUIRED));
        return ops;
    }
}
