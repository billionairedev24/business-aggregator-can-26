package ca.northline.merchants.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.PilotCohort;
import ca.northline.merchants.api.SellerSanctions;
import ca.northline.merchants.application.PilotStore.Facts;
import ca.northline.merchants.application.PilotStore.PilotRow;
import ca.northline.merchants.domain.KitchenVisit;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.PilotInvite;
import ca.northline.merchants.domain.Province;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Regions;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.PlatformRoles;
import ca.northline.shared.security.StaffRole;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PilotCohort} and {@link PilotInviteLinks} (S-120). The pilot row keeps only what no other table knows; the
 * business's facts are read from its own rows on every view. Invites reuse the team invitation scheme (a random
 * 256-bit token, its SHA-256 stored, expiring, single use). A pilot business in a market that isn't live is hidden from
 * search until the market opens. Every change is in the platform audit log in the same transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional
class PilotCohortService implements PilotCohort, PilotInviteLinks {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> BLOCKER_OWNERS = Set.of("business", "northline", "stripe", "inspector");
    private static final Set<String> TYPES = Set.of("provider", "seller", "kitchen", "both");
    private static final String PILOT = "pilot";

    private final PilotStore store;
    private final KitchenVisitStore visits;
    private final KitchenVisitPolicy kitchenVisits;
    private final Regions regions;
    private final PlatformRoles platformRoles;
    private final SellerSanctions sanctions;
    private final StudioLinks links;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ── reads ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<Pilot> list(@Nullable String marketId) {
        return views(store.pilots(blankToNull(marketId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Pilot> find(String pilotId) {
        return store.pilot(pilotId).map(row -> views(List.of(row)).getFirst());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Note> notes(String pilotId) {
        row(pilotId);
        return store.notes(pilotId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invite> invites(String pilotId) {
        row(pilotId);
        var now = clock.instant();
        return store.invites(pilotId).stream().map(i -> view(i, now)).toList();
    }

    private List<Pilot> views(List<PilotRow> rows) {
        var merchantIds = rows.stream()
                .map(PilotRow::merchantId)
                .filter(java.util.Objects::nonNull)
                .toList();
        var facts = store.facts(merchantIds);
        var latestVisits = visits.latest(merchantIds);
        var invites = store.latestInvites(rows.stream().map(PilotRow::id).toList());
        var now = clock.instant();
        return rows.stream()
                .map(r -> {
                    var invite = invites.get(r.id());
                    var merchantId = r.merchantId();
                    var f = merchantId == null ? null : facts.get(merchantId);
                    return new Pilot(
                            r.id(),
                            r.marketId(),
                            r.businessType(),
                            r.label(),
                            merchantId,
                            r.ownerId(),
                            r.blocker(),
                            r.blockerOwner(),
                            r.blockerSince(),
                            r.createdAt(),
                            invite == null ? null : view(invite, now),
                            f == null ? null : business(r, f, latestVisits.get(f.merchantId())));
                })
                .toList();
    }

    private Business business(PilotRow row, Facts f, @Nullable KitchenVisit visit) {
        return new Business(
                f.displayName(),
                f.type(),
                f.status(),
                f.onboardingStep(),
                f.province(),
                f.city(),
                f.detailsComplete(),
                f.checksComplete(),
                f.checksTotal(),
                f.rejectedChecks(),
                f.identity(),
                f.identityInReview(),
                f.registryReviewsOpen(),
                kitchenVisits.required(f.type(), f.province(), row.marketId(), f.categories()),
                f.siteVisit(),
                slot(f.siteVisitReference()),
                visit == null ? null : KitchenVisitService.view(visit),
                f.submittedAt(),
                f.approvedAt(),
                f.storefrontPublished(),
                f.searchHidden());
    }

    // ── invites ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Invited invite(NewInvite command, Actor actor) {
        var market = openMarket(command.marketId());
        if (!TYPES.contains(command.businessType())) {
            throw RuleViolation.of("businessType", "required", TYPE_REQUIRED);
        }
        var label = command.label().strip();
        if (label.isEmpty() || label.length() > 80) {
            throw RuleViolation.of("label", "length", LABEL_REQUIRED);
        }
        var email = email(command.email());
        var language = language(command.language());
        var owner = staffOrNull(command.ownerId(), "ownerId");
        var now = clock.instant();
        var row = new PilotRow(
                Ids.next(),
                market.id(),
                command.businessType(),
                label,
                null,
                owner,
                null,
                null,
                null,
                actor.userId(),
                now);
        store.insert(row);
        record(actor, null, "pilot.created", row.id(), Map.of("marketId", market.id(), "type", row.businessType()));
        return issue(row, email, language, actor, now);
    }

    @Override
    public Invited reinvite(String pilotId, @Nullable String email, String language, Actor actor) {
        var row = store.lock(pilotId).orElseThrow(() -> new NotFound("pilot business", pilotId));
        if (row.merchantId() != null) {
            throw new Conflict("pilot_started", ALREADY_ONBOARDING);
        }
        var previous = store.invites(pilotId).stream().findFirst();
        var to = email == null || email.isBlank()
                ? previous.map(PilotInvite::email)
                        .orElseThrow(() -> RuleViolation.of("email", "required", EMAIL_REQUIRED))
                : email(email);
        var now = clock.instant();
        store.revokePending(pilotId, now);
        return issue(row, to, language(language), actor, now);
    }

    private Invited issue(PilotRow row, String email, String language, Actor actor, Instant now) {
        var token = token();
        var invite = new PilotInvite(
                Ids.next(), row.id(), email, actor.userId(), now, now.plus(PilotInvite.TTL), null, null);
        store.insertInvite(invite, TeamManagementService.hash(token));
        record(actor, null, "pilot.invited", row.id(), Map.of("inviteId", invite.id(), "language", language));
        // emailed after commit by PilotInviteDelivery; the console shows the link as well, to share another way
        events.publishEvent(
                new PilotInviteIssued(Ids.next(), now, invite.id(), row.id(), actor.userId(), token, language));
        return new Invited(views(List.of(row)).getFirst(), links.pilotInvite(token), invite.expiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public PilotInvitePreview preview(String token) {
        var invite = byToken(token);
        var row = row(invite.pilotId());
        var market = regions.marketById(row.marketId()).orElseThrow(() -> new NotFound("invite", "token"));
        return new PilotInvitePreview(
                row.businessType(),
                row.label(),
                market.id(),
                market.city(),
                market.province(),
                invite.expiresAt(),
                invite.state(clock.instant()).code());
    }

    @Override
    @Transactional(readOnly = true)
    public void check(@Nullable String token, MerchantType type, Province province) {
        if (token != null && !token.isBlank()) {
            usable(byToken(token), type, province);
        }
    }

    @Override
    public void accept(String token, String merchantId, String userId, MerchantType type, Province province) {
        var invite = byToken(token);
        var row = usable(invite, type, province);
        var now = clock.instant();
        store.acceptInvite(invite.id(), userId, now);
        store.linkMerchant(row.id(), merchantId, now);
        if (!marketLive(row.marketId())) {
            store.hideBeforeLaunch(merchantId, now);
        }
        audit.record(new AuditTrail.Entry(
                merchantId,
                userId,
                "owner",
                "pilot.invite_accepted",
                "pilot",
                row.id(),
                null,
                Map.of("inviteId", invite.id(), "marketId", row.marketId())));
    }

    private PilotRow usable(PilotInvite invite, MerchantType type, Province province) {
        invite.requireUsable(clock.instant());
        var row = row(invite.pilotId());
        if (row.merchantId() != null) {
            throw new Conflict("pilot_invite_used", "This invite was already used.");
        }
        if (!row.businessType().equals(type.code())) {
            throw RuleViolation.of("type", "pilot", TYPE_MISMATCH);
        }
        var market = regions.marketById(row.marketId());
        if (market.isPresent() && !market.get().province().equalsIgnoreCase(province.code())) {
            throw RuleViolation.of("province", "pilot", PROVINCE_MISMATCH);
        }
        return row;
    }

    private PilotInvite byToken(String token) {
        return store.inviteByTokenHash(TeamManagementService.hash(token))
                .orElseThrow(() -> new NotFound("invite", "token"));
    }

    // ── enrolment, owners, blockers, notes ────────────────────────────────────────────────────────────────────────

    @Override
    public Pilot enrol(String merchantId, String marketId, Actor actor) {
        var market = openMarket(marketId);
        var facts = store.facts(List.of(merchantId)).get(merchantId);
        if (facts == null) {
            throw new NotFound("business", merchantId);
        }
        if (store.byMerchant(merchantId).isPresent()) {
            throw new Conflict("already_pilot", ALREADY_PILOT);
        }
        var now = clock.instant();
        var label = facts.displayName().length() > 80 ? facts.displayName().substring(0, 80) : facts.displayName();
        var row = new PilotRow(
                Ids.next(), market.id(), facts.type(), label, merchantId, null, null, null, null, actor.userId(), now);
        store.insert(row);
        if (!market.live() && facts.searchHidden() == null) {
            if ("active".equals(facts.status())) {
                sanctions.hideFromSearch(merchantId, PILOT, "Pilot business: shown when the market opens.");
            } else {
                store.hideBeforeLaunch(merchantId, now);
            }
        }
        record(actor, merchantId, "pilot.enrolled", row.id(), Map.of("marketId", market.id()));
        return views(List.of(row)).getFirst();
    }

    @Override
    public Pilot assign(String pilotId, @Nullable String ownerId, Actor actor) {
        var row = store.lock(pilotId).orElseThrow(() -> new NotFound("pilot business", pilotId));
        var owner = staffOrNull(ownerId, "ownerId");
        store.owner(pilotId, owner, clock.instant());
        var after = new HashMap<String, Object>();
        if (owner != null) {
            after.put("ownerId", owner);
        }
        record(actor, row.merchantId(), "pilot.owner_changed", pilotId, after);
        return views(List.of(row(pilotId))).getFirst();
    }

    @Override
    public Pilot block(String pilotId, @Nullable String text, @Nullable String blockerOwner, Actor actor) {
        var row = store.lock(pilotId).orElseThrow(() -> new NotFound("pilot business", pilotId));
        var now = clock.instant();
        if (text == null || text.isBlank()) {
            store.blocker(pilotId, null, null, null, now);
            record(actor, row.merchantId(), "pilot.unblocked", pilotId, Map.of());
        } else {
            var body = text.strip();
            if (body.length() > 300) {
                throw RuleViolation.of("text", "length", BLOCKER_REQUIRED);
            }
            if (blockerOwner == null || !BLOCKER_OWNERS.contains(blockerOwner)) {
                throw RuleViolation.of("owner", "required", BLOCKER_OWNER_REQUIRED);
            }
            var since = row.blocker() == null ? now : java.util.Objects.requireNonNullElse(row.blockerSince(), now);
            store.blocker(pilotId, body, blockerOwner, since, now);
            record(actor, row.merchantId(), "pilot.blocked", pilotId, Map.of("owner", blockerOwner));
        }
        return views(List.of(row(pilotId))).getFirst();
    }

    @Override
    public Note note(String pilotId, String body, Actor actor) {
        var row = row(pilotId);
        var text = body.strip();
        if (text.isEmpty() || text.length() > 2000) {
            throw RuleViolation.of("body", "length", NOTE_REQUIRED);
        }
        var note = new Note(Ids.next(), actor.userId(), text, clock.instant());
        store.insertNote(pilotId, note);
        record(actor, row.merchantId(), "pilot.note_added", pilotId, Map.of("noteId", note.id()));
        return note;
    }

    @Override
    public int marketLaunched(String marketId, Actor actor) {
        var ids = new java.util.LinkedHashSet<String>();
        for (var row : store.pilots(marketId)) {
            if (row.merchantId() != null) {
                ids.add(row.merchantId());
            }
        }
        // S-118: businesses a rollback hid (any business of the market, pilot cohort or not)
        regions.marketById(marketId)
                .ifPresent(m -> store.marketBusinesses(m.province(), m.city()).forEach((id, cause) -> {
                    if (PILOT.equals(cause)) {
                        ids.add(id);
                    }
                }));
        var shown = 0;
        for (var merchantId : ids) {
            if (sanctions.restoreSearch(merchantId, PILOT, "The pilot market opened to customers.")) {
                shown++;
            }
        }
        if (shown > 0) {
            record(actor, null, "pilot.market_launched", marketId, Map.of("shown", shown));
        }
        return shown;
    }

    @Override
    public int marketPaused(String marketId, Actor actor) {
        var market = regions.marketById(marketId).orElseThrow(() -> new NotFound("market", marketId));
        var hidden = 0;
        for (var e : store.marketBusinesses(market.province(), market.city()).entrySet()) {
            // hideFromSearch skips businesses that aren't active or are already hidden (another cause is kept)
            if (e.getValue() == null
                    && sanctions.hideFromSearch(
                            e.getKey(), PILOT, "The market went back to pilot: hidden until it reopens.")) {
                hidden++;
            }
        }
        record(actor, null, "pilot.market_paused", marketId, Map.of("hidden", hidden));
        return hidden;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

    private PilotRow row(String pilotId) {
        return store.pilot(pilotId).orElseThrow(() -> new NotFound("pilot business", pilotId));
    }

    /** A market where businesses can onboard: it exists and neither it nor its province is off. */
    private MarketProfile openMarket(@Nullable String marketId) {
        var market = regions.marketById(blankToNull(marketId))
                .filter(m -> m.status() != LaunchStatus.OFF)
                .filter(m -> regions.province(m.province())
                        .map(p -> p.status() != LaunchStatus.OFF)
                        .orElse(false))
                .orElse(null);
        if (market == null) {
            throw RuleViolation.of("marketId", "required", MARKET_REQUIRED);
        }
        return market;
    }

    private boolean marketLive(String marketId) {
        return regions.marketById(marketId).map(MarketProfile::live).orElse(false);
    }

    private @Nullable String staffOrNull(@Nullable String userId, String field) {
        var id = blankToNull(userId);
        if (id != null && !platformRoles.of(id).contains(StaffRole.STAFF)) {
            throw RuleViolation.of(field, "staff", OWNER_NOT_STAFF);
        }
        return id;
    }

    private static String email(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw RuleViolation.of("email", "required", EMAIL_REQUIRED);
        }
        var email = raw.strip();
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw RuleViolation.of("email", "format", EMAIL_FORMAT);
        }
        return email;
    }

    private static String language(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "en";
        }
        if (!raw.equals("en") && !raw.equals("fr")) {
            throw RuleViolation.of("language", "option", LANGUAGE_REQUIRED);
        }
        return raw;
    }

    private void record(
            Actor actor, @Nullable String merchantId, String action, String targetId, Map<String, ?> after) {
        audit.record(
                new AuditTrail.Entry(merchantId, actor.userId(), actor.role(), action, "pilot", targetId, null, after));
    }

    private static Invite view(PilotInvite i, Instant now) {
        return new Invite(
                i.id(),
                i.email(),
                i.createdAt(),
                i.expiresAt(),
                i.acceptedAt(),
                i.state(now).code());
    }

    private static @Nullable Instant slot(@Nullable String reference) {
        if (reference == null) {
            return null;
        }
        try {
            return Instant.parse(reference);
        } catch (DateTimeParseException _) {
            return null; // "visit:<id>" once a visit passed
        }
    }

    private static String token() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
