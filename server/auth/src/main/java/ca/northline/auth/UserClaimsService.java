package ca.northline.auth;

import org.springframework.stereotype.Service;
import java.util.Map;

@Service
class UserClaimsService {
    /** Reads identity.users / merchant memberships / staff roles. Business + staff tokens require acr=mfa. */
    Map<String, Object> claimsFor(String subject) {
        // TODO(implement): query auth schema; return roles, merchants, acr
        return Map.of();
    }
}
