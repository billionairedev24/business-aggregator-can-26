package ca.northline.auth.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partner tokens (S-30): at most {@code northline.auth.rate-limits.limits.partner-token} per partner (and per IP)
 * — {@code 429 rate_limited} over it — and one audit row per token issued ({@code developer.audit_log}
 * {@code auth.partner_token_issued}: actor and target = the partner's client id, scopes, merchants, expiry).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerTokens {

    static final String ISSUED = "auth.partner_token_issued";

    private final AttemptLimits limits;
    private final AuditTrail audit;

    /** Counts a token request of an authenticated partner; {@link FlowRejected} (rate limited) when over the limit. */
    public void beforeIssuing(String clientId) {
        limits.consume(LimitedAction.PARTNER_TOKEN, new AttemptLimits.Subject("partner:" + clientId, null));
    }

    /** A token was issued: one audit row. */
    @Transactional
    public void issued(String clientId, List<String> scopes, List<String> merchants, Instant expiresAt) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("scopes", scopes);
        detail.put("merchants", merchants);
        detail.put("expiresAt", expiresAt.toString());
        audit.record(clientId, ISSUED, "oauth_client", clientId, detail);
        log.info("Partner token issued: client={} scopes={} merchants={}", clientId, scopes, merchants.size());
    }
}
