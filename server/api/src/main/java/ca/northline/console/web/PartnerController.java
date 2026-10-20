package ca.northline.console.web;

import ca.northline.developer.api.PartnerClients;
import ca.northline.developer.api.PartnerClients.NewPartner;
import ca.northline.developer.api.PartnerClients.Partner;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — API &amp; webhooks › Partners (S-158): partner API clients (S-30's {@code partner:<name>},
 * client credentials with {@code private_key_jwt}) registered here instead of in configuration. Admins (screen
 * {@code api}, action {@code keys}) register one with its JWK Set URL, scopes and the businesses it asks for; owners
 * approve in their Studio. Every change is audit-logged (platform-level; access requests under the business).
 *
 * <pre>
 * GET    /api/v1/console/partners                                   {items: [PartnerRow]}
 * POST   /api/v1/console/partners {name, displayName, jwkSetUrl, scopes, tokenTtlSeconds?, merchantIds}  201 PartnerRow
 *        422 per field (merchantIds: unknown business) · 409 partner_exists
 * POST   /api/v1/console/partners/{id}/rotate {jwkSetUrl?}         PartnerRow
 * POST   /api/v1/console/partners/{id}/disable | /enable           PartnerRow
 * POST   /api/v1/console/partners/{id}/businesses {merchantIds}    PartnerRow (asks them for consent)
 * DELETE /api/v1/console/partners/{id}/businesses/{merchantId}     PartnerRow
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/partners")
@RequiredArgsConstructor
class PartnerController {

    static final String UNKNOWN_BUSINESS = "No business with this id.";

    private final PartnerClients partners;
    private final BusinessNames businesses;

    record RegisterRequest(
            @Nullable String name,
            @Nullable String displayName,
            @Nullable String jwkSetUrl,
            @Nullable List<String> scopes,
            @Nullable Integer tokenTtlSeconds,
            @Nullable List<String> merchantIds) {}

    record RotateRequest(@Nullable String jwkSetUrl) {}

    record BusinessesRequest(@Nullable List<String> merchantIds) {}

    record BindingRow(
            String merchantId,
            @Nullable String businessName,
            String state,
            Instant requestedAt,
            @Nullable Instant decidedAt) {}

    record PartnerRow(
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
            List<BindingRow> businesses) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.API)
    ListResponse<PartnerRow> all() {
        return new ListResponse<>(partners.all().stream().map(this::row).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.API, actions = ConsoleAction.KEYS)
    PartnerRow register(@RequestBody RegisterRequest body, CurrentStaff staff) {
        var merchants = known(body.merchantIds());
        return row(partners.register(
                new NewPartner(
                        Objects.requireNonNullElse(body.name(), ""),
                        Objects.requireNonNullElse(body.displayName(), ""),
                        Objects.requireNonNullElse(body.jwkSetUrl(), ""),
                        Objects.requireNonNullElse(body.scopes(), List.of()),
                        body.tokenTtlSeconds(),
                        merchants),
                staff.userId(),
                staff.roleCodes()));
    }

    @PostMapping("/{id}/rotate")
    @RequiresConsole(value = ConsoleScreen.API, actions = ConsoleAction.KEYS)
    PartnerRow rotate(
            @PathVariable String id, @RequestBody(required = false) @Nullable RotateRequest body, CurrentStaff staff) {
        return row(partners.rotate(id, body == null ? null : body.jwkSetUrl(), staff.userId(), staff.roleCodes()));
    }

    @PostMapping("/{id}/disable")
    @RequiresConsole(value = ConsoleScreen.API, actions = ConsoleAction.KEYS)
    PartnerRow disable(@PathVariable String id, CurrentStaff staff) {
        return row(partners.disable(id, staff.userId(), staff.roleCodes()));
    }

    @PostMapping("/{id}/enable")
    @RequiresConsole(value = ConsoleScreen.API, actions = ConsoleAction.KEYS)
    PartnerRow enable(@PathVariable String id, CurrentStaff staff) {
        return row(partners.enable(id, staff.userId(), staff.roleCodes()));
    }

    @PostMapping("/{id}/businesses")
    @RequiresConsole(value = ConsoleScreen.API, actions = ConsoleAction.KEYS)
    PartnerRow request(@PathVariable String id, @RequestBody BusinessesRequest body, CurrentStaff staff) {
        return row(partners.request(id, known(body.merchantIds()), staff.userId(), staff.roleCodes()));
    }

    @DeleteMapping("/{id}/businesses/{merchantId}")
    @RequiresConsole(value = ConsoleScreen.API, actions = ConsoleAction.KEYS)
    PartnerRow remove(@PathVariable String id, @PathVariable String merchantId, CurrentStaff staff) {
        return row(partners.remove(id, merchantId, staff.userId(), staff.roleCodes()));
    }

    /** The businesses must exist (422 otherwise); an empty list is the use case's own 422. */
    private List<String> known(@Nullable List<String> ids) {
        var list = ids == null
                ? List.<String>of()
                : ids.stream()
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
        for (var id : list) {
            if (businesses.displayName(id).isEmpty()) {
                throw RuleViolation.of("merchantIds", "unknown", UNKNOWN_BUSINESS);
            }
        }
        return list;
    }

    private PartnerRow row(Partner p) {
        return new PartnerRow(
                p.id(),
                p.name(),
                p.clientId(),
                p.displayName(),
                p.jwkSetUrl(),
                p.scopes(),
                p.tokenTtlSeconds(),
                p.state(),
                p.keyVersion(),
                p.rotatedAt(),
                p.createdAt(),
                p.bindings().stream()
                        .map(b -> new BindingRow(
                                b.merchantId(),
                                businesses.displayName(b.merchantId()).orElse(null),
                                b.state(),
                                b.requestedAt(),
                                b.decidedAt()))
                        .toList());
    }
}
