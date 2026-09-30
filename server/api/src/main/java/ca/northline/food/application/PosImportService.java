package ca.northline.food.application;

import ca.northline.food.application.MenuStore.ItemRow;
import ca.northline.food.application.MenuStore.SectionRow;
import ca.northline.food.application.PosImportUseCases.ImportFromPos;
import ca.northline.food.application.PosImportUseCases.ManagePosConnections;
import ca.northline.food.application.PosImportViews.Applied;
import ca.northline.food.application.PosImportViews.ConnectStart;
import ca.northline.food.application.PosImportViews.ConnectionView;
import ca.northline.food.application.PosImportViews.Preview;
import ca.northline.food.application.PosMenuSource.Credentials;
import ca.northline.food.application.PosMenuSource.GrantRevoked;
import ca.northline.food.application.PosMenuSource.PosMenu;
import ca.northline.food.application.PosStore.Connection;
import ca.northline.food.application.PosStore.ImportRow;
import ca.northline.food.application.PosStore.Link;
import ca.northline.food.application.PosStore.LinkKind;
import ca.northline.food.application.PosStore.OAuthRequest;
import ca.northline.food.application.PosStore.Status;
import ca.northline.food.domain.ItemStatus;
import ca.northline.food.domain.ItemWindow;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.food.domain.PosProvider;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.crypto.SecretSealer;
import ca.northline.shared.integration.OAuthCallback;
import ca.northline.shared.integration.ProviderHttp;
import ca.northline.shared.security.MerchantMemberships;
import ca.northline.shared.security.MerchantPermission;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Import from POS" (S-36). Connect: Square and Clover over OAuth (single-use 256-bit state stored hashed, bound to the
 * owner; the callback is the shared one on the api host, where Square's one redirect URL also serves the S-35
 * catalogue sync), Toast by restaurant GUID (partner access). Tokens sealed with the envelope key. Import: the POS menu
 * is read into a preview with the diff against earlier imports; "Apply" writes it in one transaction. New items are
 * drafts with allergens not declared — the kitchen confirms them in the item editor before anything goes live.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PosImportService implements ManagePosConnections, ImportFromPos, OAuthCallback {

    static final Duration REQUEST_TTL = Duration.ofMinutes(10);
    static final Duration PREVIEW_TTL = Duration.ofHours(1);
    static final Pattern TOAST_GUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PosStore store;
    private final List<PosMenuSource> sourceList;
    private final PosSettings settings;
    private final MenuStore menus;
    private final ModifierGroupStore groups;
    private final MenuBuilderService builder;
    private final SecretSealer sealer;
    private final MerchantMemberships memberships;
    private final Clock clock;

    record Stored(
            String accessToken,
            @Nullable String refreshToken,
            @Nullable Instant expiresAt,
            String account) {}

    private PosMenuSource source(PosProvider provider) {
        return sourceList.stream()
                .filter(s -> s.provider() == provider)
                .findFirst()
                .orElseThrow(() -> new Conflict("pos_unavailable", "This POS can't be connected yet."));
    }

    private boolean available(PosProvider provider) {
        return sourceList.stream().anyMatch(s -> s.provider() == provider && s.available());
    }

    // ── connections ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ConnectionView> connections(String merchantId) {
        var stored = store.connections(merchantId);
        return Arrays.stream(PosProvider.values())
                .map(p -> view(
                        p,
                        stored.stream()
                                .filter(c -> c.provider() == p)
                                .findFirst()
                                .orElse(null)))
                .toList();
    }

    private ConnectionView view(PosProvider p, @Nullable Connection c) {
        var kind = p.oauth() ? "oauth" : "restaurant_id";
        if (c == null) {
            return new ConnectionView(p, kind, available(p), "disconnected", null, null, null);
        }
        var connected = c.status() != Status.DISCONNECTED;
        return new ConnectionView(
                p,
                kind,
                available(p),
                c.status().name().toLowerCase(java.util.Locale.ROOT),
                connected ? c.accountLabel() : null,
                connected ? c.connectedAt() : null,
                c.lastImportAt());
    }

    @Override
    @Transactional
    public ConnectStart connect(
            String merchantId,
            String userId,
            PosProvider provider,
            @Nullable String menuId,
            @Nullable String restaurantId) {
        if (!available(provider)) {
            throw new Conflict("pos_unavailable", "This POS can't be connected yet.");
        }
        var source = source(provider);
        if (provider.oauth()) {
            var state = random();
            var now = clock.instant();
            store.saveRequest(
                    new OAuthRequest(sha256(state), merchantId, userId, provider, menuId, now, now.plus(REQUEST_TTL)));
            return new ConnectStart(source.authorizationUrl(state, settings.redirectUri(provider)), null);
        }
        var guid = restaurantId == null ? "" : restaurantId.strip();
        if (!TOAST_GUID.matcher(guid).matches()) {
            throw RuleViolation.of("restaurantId", "format", KitchenMessages.TOAST_RESTAURANT);
        }
        PosMenuSource.Grant grant;
        try {
            grant = source.link(guid.toLowerCase(java.util.Locale.ROOT));
        } catch (PosMenuSource.Unverified | GrantRevoked e) {
            log.info("Toast restaurant {} not readable for {}: {}", guid, merchantId, e.getMessage());
            throw RuleViolation.of("restaurantId", "linked", KitchenMessages.TOAST_NOT_LINKED);
        }
        var id = store.connection(merchantId, provider).map(Connection::id).orElseGet(Ids::next);
        var saved = store.saveGrant(
                id, merchantId, provider, grant.accountId(), grant.accountLabel(), null, clock.instant());
        return new ConnectStart(null, view(provider, saved));
    }

    /** The shared OAuth callback: completes a Square / Clover consent this module started. */
    @Override
    @Transactional
    public Optional<URI> complete(String platform, Map<String, String> params) {
        PosProvider provider;
        try {
            provider = CodedEnum.fromCode(PosProvider.class, platform);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        var state = params.get("state");
        if (state == null || !provider.oauth()) {
            return Optional.empty();
        }
        var request = store.takeRequest(sha256(state), clock.instant()).orElse(null);
        if (request == null) {
            return Optional.empty(); // not ours (the catalogue sync shares Square's callback), or expired
        }
        var merchantId = request.merchantId();
        if (request.provider() != provider) {
            return back(request, "failed");
        }
        var role = memberships.roleOf(merchantId, request.userId());
        if (role.isEmpty() || !role.get().grants(MerchantPermission.MANAGE)) {
            log.warn("POS callback refused: {} can no longer manage {}", request.userId(), merchantId);
            return back(request, "failed");
        }
        if (params.get("error") != null || params.get("code") == null) {
            return back(request, "access_denied".equals(params.get("error")) ? "denied" : "failed");
        }
        PosMenuSource.Grant grant;
        try {
            grant = source(provider).exchange(params, settings.redirectUri(provider));
        } catch (RuntimeException e) {
            log.warn("POS code exchange with {} failed: {}", provider.code(), e.getMessage());
            return back(request, "failed");
        }
        var id = store.connection(merchantId, provider).map(Connection::id).orElseGet(Ids::next);
        store.saveGrant(
                id,
                merchantId,
                provider,
                grant.accountId(),
                grant.accountLabel(),
                seal(grant.credentials(), id),
                clock.instant());
        return back(request, "connected");
    }

    private Optional<URI> back(OAuthRequest request, String result) {
        var q = ProviderHttp.params();
        q.put("pos", request.provider().code());
        q.put("result", result);
        if (request.menuId() != null) {
            q.put("menu", request.menuId());
        }
        return Optional.of(
                URI.create(settings.studio("/b/" + ProviderHttp.encode(request.merchantId()) + "/kitchen/menu")
                        + ProviderHttp.query(q)));
    }

    @Override
    @Transactional
    public ConnectionView disconnect(String merchantId, PosProvider provider) {
        var connection = store.connection(merchantId, provider).orElse(null);
        if (connection != null && connection.status() != Status.DISCONNECTED) {
            try {
                open(connection.id()).ifPresent(source(provider)::revoke);
            } catch (RuntimeException e) {
                log.warn("POS {}: revoking failed (token destroyed anyway): {}", provider.code(), e.getMessage());
            }
            store.disconnect(connection.id(), clock.instant());
        }
        return view(provider, store.connection(merchantId, provider).orElse(null));
    }

    // ── import ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Not transactional: the POS read may take a while, and a refused grant must stay marked. */
    @Override
    public Preview preview(String merchantId, String menuId, PosProvider provider, String actorId) {
        if (menus.menu(merchantId, menuId).isEmpty()) {
            throw new NotFound("menu", menuId);
        }
        var connection = store.connection(merchantId, provider)
                .filter(c -> c.status() != Status.DISCONNECTED)
                .orElseThrow(() -> new Conflict("not_connected", "Connect your POS first."));
        if (connection.status() == Status.RECONNECT) {
            throw new Conflict("reconnect_required", "Reconnect your POS to import.");
        }
        var menu = read(connection);
        var diff = PosImportPlanner.plan(menu, current(merchantId, menuId, provider));
        var now = clock.instant();
        var row = new ImportRow(Ids.next(), merchantId, menuId, provider, "preview", menu, diff, actorId, now, null);
        store.saveImport(row);
        return preview(row);
    }

    private PosMenu read(Connection connection) {
        var source = source(connection.provider());
        if (!connection.provider().oauth()) {
            return source.menu(new Credentials("", null, null, Objects.requireNonNull(connection.accountId())));
        }
        try {
            var stored = open(connection.id()).orElseThrow(() -> new GrantRevoked("no credentials stored"));
            var fresh = source.refresh(stored);
            if (!fresh.equals(stored)) {
                store.replaceCredentials(connection.id(), seal(fresh, connection.id()));
            }
            return source.menu(fresh);
        } catch (GrantRevoked e) {
            store.setStatus(connection.id(), Status.RECONNECT, clock.instant());
            log.info(
                    "POS {} of {} needs a reconnect: {}",
                    connection.provider().code(),
                    connection.merchantId(),
                    e.getMessage());
            throw new Conflict("reconnect_required", "Reconnect your POS to import.");
        }
    }

    private PosImportPlanner.Current current(String merchantId, String menuId, PosProvider provider) {
        var items = menus.items(merchantId, menuId).stream()
                .collect(Collectors.toMap(ItemRow::id, i -> i, (a, _) -> a, LinkedHashMap::new));
        var groupIds = groups.groups(merchantId).stream().map(g -> g.id()).collect(Collectors.toSet());
        return new PosImportPlanner.Current(
                menus.sections(merchantId, menuId),
                items,
                groupIds,
                store.links(merchantId, provider, LinkKind.SECTION, menuId),
                store.links(merchantId, provider, LinkKind.ITEM, menuId),
                store.links(merchantId, provider, LinkKind.GROUP, ""));
    }

    @Override
    @Transactional(readOnly = true)
    public Preview view(String merchantId, String importId) {
        return preview(store.importRow(merchantId, importId).orElseThrow(() -> new NotFound("import", importId)));
    }

    @Override
    @Transactional
    public Preview discard(String merchantId, String importId) {
        var row = pending(merchantId, importId);
        store.markImport(row.id(), "discarded", clock.instant());
        return view(merchantId, importId);
    }

    private ImportRow pending(String merchantId, String importId) {
        var row = store.importRow(merchantId, importId).orElseThrow(() -> new NotFound("import", importId));
        if (!row.status().equals("preview")) {
            throw new Conflict("import_closed", "This import was already applied or discarded.");
        }
        if (row.createdAt().plus(PREVIEW_TTL).isBefore(clock.instant())) {
            throw new Conflict(
                    "preview_expired", "This preview is more than an hour old. Import again to see the latest menu.");
        }
        return row;
    }

    /**
     * Writes the preview: re-planned from the stored POS menu against the kitchen's data now, in one transaction.
     * Groups first (items reference them), then sections, then items; removed items go back to draft.
     */
    @Override
    @Transactional
    public Applied apply(String merchantId, String importId) {
        var row = pending(merchantId, importId);
        var provider = row.provider();
        var menuId = row.menuId();
        var menu = row.menu();
        var now = clock.instant();
        var plan = PosImportPlanner.plan(menu, current(merchantId, menuId, provider));

        // groups
        var groupLocal = new HashMap<String, String>();
        var optionLinks = store.links(merchantId, provider, LinkKind.OPTION, "");
        var groupLinks = store.links(merchantId, provider, LinkKind.GROUP, "");
        int groupsCreated = 0;
        int groupsUpdated = 0;
        int skipped = 0;
        for (var change : plan.groups()) {
            if (change.change().equals("problem")) {
                skipped++;
                continue;
            }
            var g = PosImportPlanner.groupOf(menu, change.externalId()).orElseThrow();
            var link = groupLinks.get(g.externalId());
            var optionIds = new HashMap<String, String>();
            for (var o : g.options()) {
                var known = optionLinks.get(o.externalId());
                optionIds.put(
                        o.externalId(),
                        known != null && change.change().equals("changed") ? known.localId() : Ids.next());
            }
            switch (change.change()) {
                case "new" -> {
                    var id = Ids.next();
                    groups.insert(PosImportPlanner.group(id, merchantId, g, optionIds, groups.nextSort(merchantId)));
                    groupLocal.put(g.externalId(), id);
                    groupsCreated++;
                }
                case "changed" -> {
                    var id = Objects.requireNonNull(link).localId();
                    var existing = groups.group(merchantId, id).orElseThrow();
                    groups.update(PosImportPlanner.group(id, merchantId, g, optionIds, existing.sort()).toBuilder()
                            .showForOptionIds(existing.showForOptionIds())
                            .build());
                    groupLocal.put(g.externalId(), id);
                    groupsUpdated++;
                }
                default ->
                    groupLocal.put(g.externalId(), Objects.requireNonNull(link).localId());
            }
            if (!change.change().equals("unchanged")) {
                store.saveLink(
                        merchantId,
                        provider,
                        new Link(
                                LinkKind.GROUP,
                                "",
                                g.externalId(),
                                Objects.requireNonNull(groupLocal.get(g.externalId())),
                                PosImportPlanner.hash(g),
                                null),
                        now);
                for (var o : g.options()) {
                    store.saveLink(
                            merchantId,
                            provider,
                            new Link(
                                    LinkKind.OPTION,
                                    "",
                                    o.externalId(),
                                    Objects.requireNonNull(optionIds.get(o.externalId())),
                                    o.name(),
                                    null),
                            now);
                }
            }
        }

        // sections
        var sectionLocal = new HashMap<String, String>();
        var existingSections = menus.sections(merchantId, menuId);
        var nextSort =
                existingSections.stream().mapToInt(SectionRow::sort).max().orElse(-1) + 1;
        int sectionsCreated = 0;
        for (var s : plan.sections()) {
            var id = s.localId();
            if (id == null) {
                id = Ids.next();
                menus.insertSection(new SectionRow(id, menuId, s.name(), nextSort++));
                sectionsCreated++;
            }
            sectionLocal.put(s.externalId(), id);
            store.saveLink(
                    merchantId,
                    provider,
                    new Link(
                            LinkKind.SECTION, menuId, s.externalId(), id, PosImportPlanner.sectionHash(s.name()), null),
                    now);
        }

        // items
        var sortBySection = new HashMap<String, Integer>();
        menus.items(merchantId, menuId).forEach(it -> sortBySection.merge(it.sectionId(), 1, Integer::sum));
        int created = 0;
        int updated = 0;
        int hidden = 0;
        for (var change : plan.items()) {
            switch (change.change()) {
                case "problem" -> skipped++;
                case "removed" -> {
                    builder.unpublish(merchantId, Objects.requireNonNull(change.localId()));
                    var link = store.links(merchantId, provider, LinkKind.ITEM, menuId)
                            .get(change.externalId());
                    if (link != null) {
                        store.saveLink(
                                merchantId,
                                provider,
                                new Link(
                                        LinkKind.ITEM,
                                        menuId,
                                        link.externalId(),
                                        link.localId(),
                                        link.contentHash(),
                                        now),
                                now);
                    }
                    hidden++;
                }
                default -> {
                    var located = locate(menu, change.externalId());
                    var it = located.item();
                    var sectionId = Objects.requireNonNull(sectionLocal.get(located.sectionExternalId()));
                    var groupIds = it.groupIds().stream()
                            .map(groupLocal::get)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList();
                    var hash = PosImportPlanner.hash(it, located.sectionExternalId());
                    String localId;
                    if (change.change().equals("new")) {
                        localId = Ids.next();
                        int sort = sortBySection.merge(sectionId, 1, Integer::sum) - 1;
                        menus.insertItem(ItemRow.builder()
                                .id(localId)
                                .merchantId(merchantId)
                                .menuId(menuId)
                                .sectionId(sectionId)
                                .name(PosImportPlanner.itemName(it.name()))
                                .description(PosImportPlanner.description(it.description()))
                                .priceCents(Objects.requireNonNull(it.priceCents()))
                                .allergens(null) // never from a POS: the kitchen confirms them
                                .dietary(List.of())
                                .prepAddMin(0)
                                .soldToday(0)
                                .availability(ItemWindow.ALWAYS)
                                .comboEligible(true)
                                .status(ItemStatus.DRAFT)
                                .vetting("draft")
                                .available(true)
                                .sort(sort)
                                .modifierGroupIds(groupIds)
                                .updatedAt(now)
                                .build());
                        created++;
                    } else {
                        localId = Objects.requireNonNull(change.localId());
                        if (change.change().equals("changed")) {
                            var local = menus.item(merchantId, localId).orElseThrow();
                            menus.updateItem(local.toBuilder()
                                    .name(PosImportPlanner.itemName(it.name()))
                                    .description(PosImportPlanner.description(it.description()))
                                    .priceCents(Objects.requireNonNull(it.priceCents()))
                                    .modifierGroupIds(groupIds)
                                    .sectionId(sectionId)
                                    .updatedAt(now)
                                    .build());
                            updated++;
                        }
                    }
                    store.saveLink(
                            merchantId,
                            provider,
                            new Link(LinkKind.ITEM, menuId, it.externalId(), localId, hash, null),
                            now);
                }
            }
        }
        store.markImport(row.id(), "applied", now);
        store.connection(merchantId, provider).ifPresent(c -> store.recordImport(c.id(), now));
        return new Applied(created, updated, hidden, sectionsCreated, groupsCreated, groupsUpdated, skipped);
    }

    private record Located(PosMenuSource.PosItem item, String sectionExternalId) {}

    private static Located locate(PosMenu menu, String externalId) {
        for (var s : menu.sections()) {
            for (var it : s.items()) {
                if (it.externalId().equals(externalId)) {
                    return new Located(it, s.externalId());
                }
            }
        }
        throw new IllegalStateException("item " + externalId + " not in the menu");
    }

    private static Preview preview(ImportRow row) {
        return new Preview(
                row.id(), row.provider(), row.menuId(), row.status(), row.diff(), row.createdAt(), row.appliedAt());
    }

    // ── credentials ────────────────────────────────────────────────────────────────────────────────────────────────

    private SecretSealer.Sealed seal(Credentials c, String connectionId) {
        return sealer.seal(
                JSON.writeValueAsString(new Stored(c.accessToken(), c.refreshToken(), c.expiresAt(), c.account())),
                connectionId);
    }

    private Optional<Credentials> open(String connectionId) {
        return store.credentials(connectionId).map(box -> {
            var s = JSON.readValue(sealer.open(box, connectionId), Stored.class);
            return new Credentials(s.accessToken(), s.refreshToken(), s.expiresAt(), s.account());
        });
    }

    static String random() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
