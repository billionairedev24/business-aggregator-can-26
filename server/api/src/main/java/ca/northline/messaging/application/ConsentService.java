package ca.northline.messaging.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.email.CommercialConsent;
import ca.northline.email.MessageClasses;
import ca.northline.identity.api.NotificationContacts;
import ca.northline.messaging.api.ConsentRetention;
import ca.northline.messaging.application.Consents.Change;
import ca.northline.messaging.application.Consents.ConsentDesk;
import ca.northline.messaging.application.Consents.ConsentStore;
import ca.northline.messaging.application.Consents.Current;
import ca.northline.messaging.application.Consents.ManageConsents;
import ca.northline.messaging.application.Consents.View;
import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.ConsentEvidence;
import ca.northline.messaging.domain.ConsentRecord;
import ca.northline.messaging.domain.ConsentSource;
import ca.northline.messaging.domain.ConsentWordings;
import ca.northline.platform.EmailProperties;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CASL consent (S-108): records grants and withdrawals with their evidence, answers what is granted now (for the
 * settings, the api's {@code Mailer} and — through the same table — the worker), serves staff's proof lookups and
 * withdrawals, and purges proofs past {@link ConsentRetention#PROOF_PERIOD} once a day.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class ConsentService implements ManageConsents, ConsentDesk, ConsentRetention, CommercialConsent {

    static final String WORDING_CHANGED = "The consent wording has changed. Read it again before you agree.";
    static final String NOT_GRANTED_HERE = "Consent can only be given by the person, where they read the wording.";

    private final ConsentStore store;
    private final NotificationContacts contacts;
    private final AuditTrail audit;
    private final EmailProperties email;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public View view(String userId, ConsentWordings.Surface surface, String language) {
        var history = store.history(userId);
        var latest = new EnumMap<ConsentCategory, ConsentRecord>(ConsentCategory.class);
        history.forEach(r -> latest.putIfAbsent(r.category(), r)); // newest first
        var categories = new ArrayList<Current>();
        for (var category : ConsentCategory.values()) {
            var wording = ConsentWordings.current(category, surface).orElse(null);
            if (wording == null) {
                continue; // nothing offered here (Studio asks for email only)
            }
            var now = latest.get(category);
            categories.add(new Current(
                    category,
                    now != null && now.granted(),
                    now == null ? null : now.at(),
                    now == null ? null : now.source(),
                    wording.version(),
                    wording.text(language, legalName())));
        }
        return new View(categories, history, requester());
    }

    @Override
    public void change(String userId, Change c) {
        var current = store.latest(userId, c.category());
        if (current.map(ConsentRecord::granted).orElse(false) == c.granted()) {
            return;
        }
        String version = null;
        String addressHash = null;
        if (c.granted()) {
            if (!ConsentSource.PERSONAL.contains(c.source())) {
                throw new IllegalArgumentException(
                        NOT_GRANTED_HERE + " (" + c.source().code() + ")");
            }
            var surface = c.source() == ConsentSource.STUDIO
                    ? ConsentWordings.Surface.STUDIO
                    : ConsentWordings.Surface.ACCOUNT;
            var wording = ConsentWordings.current(c.category(), surface)
                    .orElseThrow(() -> RuleViolation.of("category", "allowed", "Choose from the list."));
            if (c.wordingVersion() != null && !c.wordingVersion().equals(wording.version())) {
                throw new Conflict("consent_wording_changed", WORDING_CHANGED);
            }
            version = wording.version();
            addressHash = address(userId, c.category());
        } else {
            addressHash = current.map(ConsentRecord::addressHash).orElse(null);
        }
        store.append(new ConsentRecord(
                Ids.next(),
                userId,
                c.category(),
                c.granted(),
                clock.instant(),
                c.source(),
                version,
                "fr".equals(c.language()) ? "fr" : "en",
                addressHash,
                c.evidence().ipPrefix(),
                c.evidence().userAgentHash(),
                null));
        log.info(
                "Consent {} {} for {} ({})",
                c.category().code(),
                c.granted() ? "granted" : "withdrawn",
                userId,
                c.source().code());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<ConsentCategory, Boolean> granted(String userId) {
        var out = new LinkedHashMap<ConsentCategory, Boolean>();
        for (var category : ConsentCategory.values()) {
            out.put(
                    category,
                    store.latest(userId, category).map(ConsentRecord::granted).orElse(false));
        }
        return out;
    }

    /** The api's {@code Mailer} asks this right before a commercial email goes. */
    @Override
    @Transactional(readOnly = true)
    public boolean allows(String userId, MessageClasses.ConsentCategory category) {
        return store.latest(userId, ConsentCategory.ofChannel(category.channel()))
                .map(ConsentRecord::granted)
                .orElse(false);
    }

    // ── Staff ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ConsentRecord> lookup(@Nullable String userId, @Nullable String contact) {
        var found = new LinkedHashMap<String, ConsentRecord>();
        if (contact != null && !contact.isBlank()) {
            var users = store.byAddressHash(ConsentEvidence.addressHash(contact)).stream()
                    .map(ConsentRecord::userId)
                    .distinct()
                    .toList();
            users.forEach(u -> store.history(u).forEach(r -> found.put(r.id(), r)));
        }
        if (userId != null && !userId.isBlank()) {
            store.history(userId.strip()).forEach(r -> found.put(r.id(), r));
        }
        return found.values().stream()
                .sorted(Comparator.comparing(ConsentRecord::at)
                        .thenComparing(ConsentRecord::id)
                        .reversed())
                .toList();
    }

    @Override
    public void withdraw(String userId, ConsentCategory category, String staffId, String staffRoles) {
        var current = store.latest(userId, category);
        if (!current.map(ConsentRecord::granted).orElse(false)) {
            return;
        }
        var record = new ConsentRecord(
                Ids.next(),
                userId,
                category,
                false,
                clock.instant(),
                ConsentSource.CONSOLE,
                null,
                null,
                current.get().addressHash(),
                null,
                null,
                staffId);
        store.append(record);
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                staffRoles,
                "consent.withdrawn",
                "user",
                userId,
                null,
                Map.of("category", category.code(), "record", record.id())));
    }

    // ── Retention ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public int purgeExpiredProofs(Instant now) {
        var before = now.atOffset(ZoneOffset.UTC).minus(PROOF_PERIOD).toInstant();
        var purged = store.purgeWithdrawnBefore(before);
        if (purged > 0) {
            log.info("Purged {} consent records withdrawn before {} (CASL proof period)", purged, before);
        }
        return purged;
    }

    @Scheduled(cron = "${northline.casl.purge-cron:0 23 4 * * *}", zone = "${northline.region.platform-zone}")
    void purgeDaily() {
        purgeExpiredProofs(clock.instant());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private @Nullable String address(String userId, ConsentCategory category) {
        var contact = contacts.contact(userId).orElse(null);
        if (contact == null) {
            return null;
        }
        var address = switch (category) {
            case MARKETING_EMAIL -> contact.email();
            case MARKETING_SMS -> contact.phone();
            case MARKETING_PUSH -> null; // the account's app installations: no address
        };
        return address == null || address.isBlank() ? null : ConsentEvidence.addressHash(address);
    }

    private String legalName() {
        var name = email.legalName();
        return name == null || name.isBlank() ? "Northline" : name.strip();
    }

    /** "Northline Marketplace Inc. · <address> · support@…": who asks for consent (CASL regulations s. 4). */
    private String requester() {
        var address =
                email.mailingAddress() == null ? "" : email.mailingAddress().strip();
        var name = legalName();
        var who = address.contains(name) ? address : name + " · " + address;
        return who + " · " + email.contact();
    }
}
