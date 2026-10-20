package ca.northline.developer.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Partner API clients registered in the platform console (S-158; S-30 had them in configuration only) and the owners'
 * consent. Each active partner becomes the OAuth client {@code partner:<name>} in northline-auth (client credentials,
 * {@code private_key_jwt} against its JWK Set URL); its tokens carry the businesses whose owner <b>approved</b> — a
 * business the console names is only a request until then. Staff register, rotate, disable and re-enable (console
 * screen {@code api}, action {@code keys}); owners approve, decline or revoke in Settings › API. Every change is
 * audit-logged: platform-level for the partner, under the business for its access.
 */
public interface PartnerClients {

    String NAME = "Use 1 to 50 lower-case letters, digits or dashes, starting with a letter or a digit.";
    String DISPLAY_NAME = "Enter the partner's name, 1 to 80 characters.";
    String JWKS = "Enter the HTTPS address of the partner's JWK Set.";
    String SCOPES = "Choose api.read, api.write or both.";
    String BUSINESSES = "Choose at least one business.";
    String TTL = "Choose a token lifetime between 1 and 60 minutes.";
    String DECISION = "Choose approve, decline or revoke.";
    String NAME_TAKEN = "A partner with this name already exists.";
    String NOT_REQUESTED = "This partner hasn't asked to access your business.";
    String DECIDED = "You already answered this request.";
    String NOT_APPROVED = "Only approved access can be revoked.";

    /** The scopes a partner may hold (design 05: {@code api.read api.write}, per business). */
    List<String> SCOPES_ALLOWED = List.of("api.read", "api.write");

    List<Partner> all();

    Optional<Partner> find(String partnerId);

    /** 422 per field; 409 {@code partner_exists}. The businesses are asked for consent. */
    Partner register(NewPartner partner, String staffId, String roles);

    /**
     * "Rotate keys": the JWK Set is read again on the partner's next token request (northline-auth drops its cached
     * keys); {@code jwkSetUrl} moves it to a new address when given.
     */
    Partner rotate(String partnerId, @Nullable String jwkSetUrl, String staffId, String roles);

    /** No new token from now on (within northline-auth's refresh); issued tokens end with their lifetime. */
    Partner disable(String partnerId, String staffId, String roles);

    Partner enable(String partnerId, String staffId, String roles);

    /** Asks more businesses for consent (businesses already asked keep their answer). */
    Partner request(String partnerId, List<String> merchantIds, String staffId, String roles);

    /** Takes a business off the partner (whatever the owner answered). */
    Partner remove(String partnerId, String merchantId, String staffId, String roles);

    /** The Studio: the partners that asked to act for this business, and the answers. */
    List<Consent> consents(String merchantId);

    /** The owner's answer: {@code approve} or {@code decline} a request, {@code revoke} an approval. */
    Consent decide(String merchantId, String partnerId, String decision, String userId, String role);

    /**
     * @param tokenTtlSeconds null = 15 minutes (design 05)
     * @param merchantIds the businesses to ask for consent
     */
    record NewPartner(
            String name,
            String displayName,
            String jwkSetUrl,
            List<String> scopes,
            @Nullable Integer tokenTtlSeconds,
            List<String> merchantIds) {
        public NewPartner {
            scopes = List.copyOf(scopes);
            merchantIds = List.copyOf(merchantIds);
        }
    }

    /**
     * @param clientId what the partner sends ({@code partner:<name>})
     * @param state {@code active | disabled}
     */
    record Partner(
            String id,
            String name,
            String clientId,
            String displayName,
            String jwkSetUrl,
            List<String> scopes,
            int tokenTtlSeconds,
            String state,
            int keyVersion,
            @Nullable Instant rotatedAt,
            Instant createdAt,
            List<Binding> bindings) {
        public Partner {
            scopes = List.copyOf(scopes);
            bindings = List.copyOf(bindings);
        }
    }

    /** @param state {@code requested | approved | declined | revoked} */
    record Binding(
            String merchantId,
            String state,
            Instant requestedAt,
            @Nullable Instant decidedAt) {}

    /**
     * @param partnerState {@code active | disabled}
     * @param state this business's answer: {@code requested | approved | declined | revoked}
     */
    record Consent(
            String partnerId,
            String displayName,
            String clientId,
            List<String> scopes,
            String partnerState,
            String state,
            Instant requestedAt,
            @Nullable Instant decidedAt) {
        public Consent {
            scopes = List.copyOf(scopes);
        }
    }
}
