package ca.northline.developer.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.developer.api.PartnerClients;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PartnerClients} (S-158). The rules are S-30's ({@code PartnerSpec}: the name, HTTPS JWK Set URL, the two
 * scopes, a lifetime up to 1 h) so northline-auth accepts every row the console writes; the businesses are requests
 * until their owner approves.
 */
@Service
@Transactional
class PartnerClientService implements PartnerClients {

    static final Pattern NAME_RULE = Pattern.compile("[a-z0-9][a-z0-9-]{0,49}");
    static final int DEFAULT_TTL = 900;

    private final PartnerClientStore store;
    private final AuditTrail audit;
    private final Clock clock;
    private final boolean allowHttp;

    PartnerClientService(
            PartnerClientStore store,
            AuditTrail audit,
            Clock clock,
            @Value("${northline.partners.allow-http-jwks:false}") boolean allowHttp) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.allowHttp = allowHttp;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Partner> all() {
        return store.all().stream().map(this::partner).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Partner> find(String partnerId) {
        return store.find(partnerId).map(this::partner);
    }

    @Override
    public Partner register(NewPartner p, String staffId, String roles) {
        var errors = new ArrayList<RuleViolation.Violation>();
        var name = p.name().strip().toLowerCase(Locale.ROOT);
        if (!NAME_RULE.matcher(name).matches()) {
            errors.add(new RuleViolation.Violation("name", "format", NAME));
        }
        var display = p.displayName().strip();
        if (display.isEmpty() || display.length() > 80) {
            errors.add(new RuleViolation.Violation("displayName", "length", DISPLAY_NAME));
        }
        var url = p.jwkSetUrl().strip();
        if (!validUrl(url)) {
            errors.add(new RuleViolation.Violation("jwkSetUrl", "format", JWKS));
        }
        var scopes = p.scopes().stream().distinct().sorted().toList();
        if (scopes.isEmpty() || !SCOPES_ALLOWED.containsAll(scopes)) {
            errors.add(new RuleViolation.Violation("scopes", "option", SCOPES));
        }
        var ttl = p.tokenTtlSeconds() == null ? DEFAULT_TTL : p.tokenTtlSeconds();
        if (ttl < 60 || ttl > 3600) {
            errors.add(new RuleViolation.Violation("tokenTtlSeconds", "range", TTL));
        }
        var merchants = p.merchantIds().stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (merchants.isEmpty()) {
            errors.add(new RuleViolation.Violation("merchantIds", "required", BUSINESSES));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        if (store.nameTaken(name)) {
            throw new Conflict("partner_exists", NAME_TAKEN);
        }
        var now = clock.instant();
        var row = new PartnerClientStore.Row(Ids.next(), name, display, url, scopes, ttl, "active", 1, null, now);
        store.insert(row, staffId);
        platform(
                staffId,
                roles,
                "partner.registered",
                row.id(),
                null,
                Map.of("clientId", "partner:" + name, "scopes", String.join(" ", scopes), "jwkSetUrl", url));
        merchants.forEach(m -> ask(row, m, staffId, roles));
        return partner(store.find(row.id()).orElseThrow());
    }

    @Override
    public Partner rotate(String partnerId, @Nullable String jwkSetUrl, String staffId, String roles) {
        var row = row(partnerId);
        var url = jwkSetUrl == null || jwkSetUrl.isBlank() ? row.jwkSetUrl() : jwkSetUrl.strip();
        if (!validUrl(url)) {
            throw RuleViolation.of("jwkSetUrl", "format", JWKS);
        }
        store.rotate(partnerId, url, clock.instant());
        platform(
                staffId,
                roles,
                "partner.keys_rotated",
                partnerId,
                Map.of("keyVersion", row.keyVersion(), "jwkSetUrl", row.jwkSetUrl()),
                Map.of("keyVersion", row.keyVersion() + 1, "jwkSetUrl", url));
        return partner(row(partnerId));
    }

    @Override
    public Partner disable(String partnerId, String staffId, String roles) {
        return state(partnerId, "disabled", "partner.disabled", staffId, roles);
    }

    @Override
    public Partner enable(String partnerId, String staffId, String roles) {
        return state(partnerId, "active", "partner.enabled", staffId, roles);
    }

    private Partner state(String partnerId, String state, String action, String staffId, String roles) {
        var row = row(partnerId);
        if (!row.state().equals(state)) {
            store.setState(partnerId, state, staffId, clock.instant());
            platform(staffId, roles, action, partnerId, Map.of("state", row.state()), Map.of("state", state));
        }
        return partner(row(partnerId));
    }

    @Override
    public Partner request(String partnerId, List<String> merchantIds, String staffId, String roles) {
        var row = row(partnerId);
        var merchants = merchantIds.stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (merchants.isEmpty()) {
            throw RuleViolation.of("merchantIds", "required", BUSINESSES);
        }
        merchants.forEach(m -> ask(row, m, staffId, roles));
        return partner(row(partnerId));
    }

    @Override
    public Partner remove(String partnerId, String merchantId, String staffId, String roles) {
        var row = row(partnerId);
        var before =
                store.binding(partnerId, merchantId).orElseThrow(() -> new NotFound("partner business", merchantId));
        store.remove(partnerId, merchantId);
        audit.record(AuditTrail.Entry.of(merchantId, staffId, roles, "partner.access_removed", "partner", row.id())
                .withChange(Map.of("state", before.state()), Map.of("clientId", "partner:" + row.name())));
        return partner(row(partnerId));
    }

    private void ask(PartnerClientStore.Row row, String merchantId, String staffId, String roles) {
        if (store.request(row.id(), merchantId, staffId, clock.instant())) {
            // under the business, so the owner sees who asked in Settings › Security › Audit log
            audit.record(AuditTrail.Entry.of(
                            merchantId, staffId, roles, "partner.access_requested", "partner", row.id())
                    .withChange(
                            null,
                            Map.of("clientId", "partner:" + row.name(), "scopes", String.join(" ", row.scopes()))));
        }
    }

    // ── the Studio ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<Consent> consents(String merchantId) {
        return store.ofBusiness(merchantId).stream()
                .map(PartnerClientService::consent)
                .toList();
    }

    @Override
    public Consent decide(String merchantId, String partnerId, String decision, String userId, String role) {
        var current = store.lockBinding(partnerId, merchantId)
                .orElseThrow(() -> new Conflict("partner_not_requested", NOT_REQUESTED));
        var next = switch (decision) {
            case "approve", "decline" -> {
                if (!"requested".equals(current.state())) {
                    throw new Conflict("partner_decided", DECIDED);
                }
                yield "approve".equals(decision) ? "approved" : "declined";
            }
            case "revoke" -> {
                if (!"approved".equals(current.state())) {
                    throw new Conflict("partner_not_approved", NOT_APPROVED);
                }
                yield "revoked";
            }
            default -> throw RuleViolation.of("decision", "option", DECISION);
        };
        store.decide(partnerId, merchantId, next, userId, clock.instant());
        var row = row(partnerId);
        audit.record(AuditTrail.Entry.of(merchantId, userId, role, "partner.access_" + next, "partner", partnerId)
                .withChange(
                        Map.of("state", current.state()), Map.of("state", next, "clientId", "partner:" + row.name())));
        return store.ofBusiness(merchantId).stream()
                .filter(b -> b.partner().id().equals(partnerId))
                .map(PartnerClientService::consent)
                .findFirst()
                .orElseThrow();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────────

    private boolean validUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException _) {
            return false;
        }
        var host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null || url.length() > 500) {
            return false;
        }
        var scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
        return "https".equals(scheme) || (allowHttp && "http".equals(scheme));
    }

    private PartnerClientStore.Row row(String partnerId) {
        return store.find(partnerId).orElseThrow(() -> new NotFound("partner", partnerId));
    }

    private void platform(
            String staffId,
            String roles,
            String action,
            String partnerId,
            @Nullable Map<String, ?> before,
            @Nullable Map<String, ?> after) {
        audit.record(new AuditTrail.Entry(null, staffId, roles, action, "partner", partnerId, before, after));
    }

    private Partner partner(PartnerClientStore.Row r) {
        return new Partner(
                r.id(),
                r.name(),
                "partner:" + r.name(),
                r.displayName(),
                r.jwkSetUrl(),
                r.scopes(),
                r.tokenTtlSeconds(),
                r.state(),
                r.keyVersion(),
                r.rotatedAt(),
                r.createdAt(),
                store.bindings(r.id()));
    }

    private static Consent consent(PartnerClientStore.BusinessBinding b) {
        var p = b.partner();
        return new Consent(
                p.id(),
                p.displayName(),
                "partner:" + p.name(),
                p.scopes(),
                p.state(),
                b.binding().state(),
                b.binding().requestedAt(),
                b.binding().decidedAt());
    }
}
