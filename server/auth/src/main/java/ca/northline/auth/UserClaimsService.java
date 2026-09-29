package ca.northline.auth;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class UserClaimsService {
    /** Reads identity.users / merchant memberships / staff roles. Business + staff tokens require acr=mfa. */
    Map<String, Object> claimsFor(String subject) {
        // TODO(implement): query auth schema; return roles, merchants, acr
        return Map.of();
    }
}
