package ca.northline.developer.application;

import ca.northline.developer.api.PartnerKeys;
import ca.northline.developer.application.DeveloperUseCases.Actor;
import ca.northline.developer.application.DeveloperUseCases.IssueApiKey;
import ca.northline.developer.application.DeveloperUseCases.RevokeApiKey;
import ca.northline.developer.domain.ApiKey;
import ca.northline.shared.NotFound;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PartnerKeys}: the Studio's own issue / revoke (same rules, same audit entries {@code api_key.issued |
 * api_key.revoked} under the business) with the staff member as the actor.
 */
@Service
@RequiredArgsConstructor
class PartnerKeyService implements PartnerKeys {

    static final int LIMIT = 500;

    private final PlatformDeveloperStore store;
    private final IssueApiKey issuer;
    private final RevokeApiKey revoker;

    @Override
    @Transactional(readOnly = true)
    public List<Key> all() {
        return store.allKeys(LIMIT).stream().map(PartnerKeyService::key).toList();
    }

    @Override
    @Transactional
    public Issued issue(String merchantId, String name, List<String> scopes, String staffId, String roles) {
        var issued = issuer.issue(new IssueApiKey.Command(new Actor(merchantId, staffId, roles), name, scopes));
        return new Issued(key(issued.key()), issued.secret());
    }

    @Override
    @Transactional
    public Key revoke(String keyId, String staffId, String roles) {
        var key = store.keyById(keyId).filter(ApiKey::active).orElseThrow(() -> new NotFound("api key", keyId));
        revoker.revoke(new Actor(key.merchantId(), staffId, roles), keyId);
        return key(store.keyById(keyId).orElseThrow());
    }

    private static Key key(ApiKey k) {
        return new Key(
                k.id(),
                k.merchantId(),
                k.name(),
                k.scopes(),
                k.prefix(),
                k.rateLimit(),
                k.createdAt(),
                k.lastUsedAt(),
                k.revokedAt());
    }
}
