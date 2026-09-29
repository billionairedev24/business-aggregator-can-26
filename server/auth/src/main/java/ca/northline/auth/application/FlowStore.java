package ca.northline.auth.application;

import ca.northline.auth.domain.PendingRegistration;
import ca.northline.auth.domain.SignInAttempt;
import java.io.Serializable;
import java.util.Optional;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;

/**
 * Outbound port: state of the flow in progress for the current browser (the auth server's HTTP session — Redis in
 * prod, so everything stored is {@link Serializable}).
 */
public interface FlowStore {

    Key<PendingRegistration> REGISTRATION = new Key<>("nl.auth.registration", PendingRegistration.class);
    Key<SignInAttempt> SIGN_IN = new Key<>("nl.auth.sign-in", SignInAttempt.class);
    Key<PublicKeyCredentialCreationOptions> PASSKEY_CREATION =
            new Key<>("nl.auth.passkey-creation", PublicKeyCredentialCreationOptions.class);
    Key<PublicKeyCredentialRequestOptions> PASSKEY_REQUEST =
            new Key<>("nl.auth.passkey-request", PublicKeyCredentialRequestOptions.class);

    <T extends Serializable> Optional<T> get(Key<T> key);

    <T extends Serializable> void put(Key<T> key, T value);

    void remove(Key<?> key);

    /** A typed session attribute name. */
    record Key<T extends Serializable>(String name, Class<T> type) {}
}
