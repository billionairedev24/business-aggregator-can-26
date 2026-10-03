package ca.northline.uat.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.shared.CodedEnum;
import ca.northline.uat.application.UatStore;
import ca.northline.uat.domain.FeedbackApp;
import ca.northline.uat.domain.FeedbackCategory;
import ca.northline.uat.domain.FeedbackState;
import ca.northline.uat.domain.Persona;
import ca.northline.uat.domain.Severity;
import ca.northline.uat.domain.SignoffOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link UatStore} over schema {@code uat} with JdbcClient. */
@Repository
@RequiredArgsConstructor
class UatJdbc implements UatStore {

    private static final String FEEDBACK = """
            select f.*, coalesce(p.label, '') as label
              from uat.feedback f left join uat.participants p on p.id = f.participant_id
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    // participants

    @Override
    public List<Participant> activeFor(String userId) {
        return jdbc.sql("select * from uat.participants where active and user_id = :user")
                .param("user", userId)
                .query(UatJdbc::participant)
                .list();
    }

    @Override
    public List<Participant> participants() {
        return jdbc.sql("select * from uat.participants order by created_at")
                .query(UatJdbc::participant)
                .list();
    }

    @Override
    public Optional<Participant> participant(String id) {
        return jdbc.sql("select * from uat.participants where id = :id")
                .param("id", id)
                .query(UatJdbc::participant)
                .optional();
    }

    @Override
    public boolean insert(Participant p) {
        var params = new HashMap<String, @Nullable Object>();
        params.put("id", p.id());
        params.put("user", p.userId());
        params.put("persona", p.persona().code());
        params.put("label", p.label());
        params.put("by", p.addedBy());
        params.put("at", ts(p.createdAt()));
        return jdbc.sql("""
                        insert into uat.participants (id, user_id, persona, label, added_by, created_at)
                        values (:id, :user, :persona, :label, :by, :at)
                        on conflict do nothing
                        """).params(params).update() == 1;
    }

    @Override
    public void deactivate(String id) {
        jdbc.sql("update uat.participants set active = false where id = :id")
                .param("id", id)
                .update();
    }

    // screenshots

    @Override
    public void insert(Screenshot s) {
        jdbc.sql("""
                        insert into uat.screenshots (id, user_id, storage_key, content_type, byte_size, created_at)
                        values (:id, :user, :key, :type, :size, :at)
                        """)
                .param("id", s.id())
                .param("user", s.userId())
                .param("key", s.storageKey())
                .param("type", s.contentType())
                .param("size", s.byteSize())
                .param("at", ts(s.createdAt()))
                .update();
    }

    @Override
    public Optional<Screenshot> screenshot(String userId, String id) {
        return jdbc.sql("select * from uat.screenshots where id = :id and user_id = :user")
                .param("id", id)
                .param("user", userId)
                .query(UatJdbc::screenshot)
                .optional();
    }

    @Override
    public void forgetScreenshot(String id) {
        jdbc.sql("delete from uat.screenshots where id = :id").param("id", id).update();
    }

    @Override
    public List<Screenshot> unsentScreenshots(String userId, Instant before) {
        return jdbc.sql("select * from uat.screenshots where user_id = :user and created_at < :before")
                .param("user", userId)
                .param("before", ts(before))
                .query(UatJdbc::screenshot)
                .list();
    }

    // feedback

    @Override
    public Feedback insert(Feedback f) {
        var params = new HashMap<String, @Nullable Object>();
        params.put("id", f.id());
        params.put("participant", f.participantId());
        params.put("persona", f.persona().code());
        params.put("user", f.userId());
        params.put("merchant", f.merchantId());
        params.put("app", f.app().code());
        params.put("category", f.category().code());
        params.put("severity", f.severity().code());
        params.put("body", f.body());
        params.put("route", f.route());
        params.put("version", f.appVersion());
        params.put("locale", f.locale());
        params.put("platform", f.platform());
        params.put("key", f.screenshotKey());
        params.put("type", f.screenshotType());
        params.put("bytes", f.screenshotBytes());
        params.put("state", f.state().code());
        params.put("at", ts(f.createdAt()));
        var number = jdbc.sql("""
                        insert into uat.feedback (id, participant_id, persona, user_id, merchant_id, app, category,
                            severity, body, route, app_version, locale, platform, screenshot_key, screenshot_type,
                            screenshot_bytes, state, created_at, updated_at)
                        values (:id, :participant, :persona, :user, :merchant, :app, :category, :severity, :body, :route,
                            :version, :locale, :platform, :key, :type, :bytes, :state, :at, :at)
                        returning number
                        """).params(params).query(Long.class).single();
        return f.withNumber(number);
    }

    @Override
    public Optional<Feedback> feedback(String id) {
        return jdbc.sql(FEEDBACK + " where f.id = :id")
                .param("id", id)
                .query(UatJdbc::feedback)
                .optional();
    }

    @Override
    public Optional<Feedback> lock(String id) {
        return jdbc.sql(FEEDBACK + " where f.id = :id for update of f")
                .param("id", id)
                .query(UatJdbc::feedback)
                .optional();
    }

    @Override
    public boolean save(Feedback f) {
        var params = new HashMap<String, @Nullable Object>();
        params.put("id", f.id());
        params.put("version", f.version());
        params.put("state", f.state().code());
        params.put("blocking", f.blocking());
        params.put("owner", f.ownerId());
        params.put("tracker", f.trackerUrl());
        params.put("duplicate", f.duplicateOf());
        params.put("at", ts(f.updatedAt()));
        return jdbc.sql("""
                        update uat.feedback
                           set state = :state, blocking = :blocking, owner_id = :owner, tracker_url = :tracker,
                               duplicate_of = :duplicate, updated_at = :at, version = version + 1
                         where id = :id and version = :version
                        """).params(params).update() == 1;
    }

    @Override
    public List<Feedback> feedbackOf(String userId) {
        return jdbc.sql(FEEDBACK + " where f.user_id = :user order by f.created_at desc limit 200")
                .param("user", userId)
                .query(UatJdbc::feedback)
                .list();
    }

    @Override
    public List<Feedback> queue(Collection<FeedbackState> states, @Nullable Boolean blocking, int limit) {
        var params = new HashMap<String, @Nullable Object>();
        params.put("all", states.isEmpty());
        params.put("states", states.stream().map(FeedbackState::code).toArray(String[]::new));
        params.put("anyBlocking", blocking == null);
        params.put("blocking", blocking);
        params.put("limit", limit);
        return jdbc.sql(FEEDBACK + """
                         where (:all or f.state = any(:states))
                           and (:anyBlocking or f.blocking is not distinct from cast(:blocking as boolean))
                         order by f.created_at desc, f.number desc
                         limit :limit
                        """).params(params).query(UatJdbc::feedback).list();
    }

    @Override
    public List<Feedback> duplicatesOf(String id) {
        return jdbc.sql(FEEDBACK + " where f.duplicate_of = :id order by f.created_at")
                .param("id", id)
                .query(UatJdbc::feedback)
                .list();
    }

    @Override
    public Map<String, Integer> duplicateCounts(Collection<String> ids) {
        var out = new HashMap<String, Integer>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select duplicate_of, count(*) as n from uat.feedback
                         where duplicate_of = any(:ids) group by duplicate_of
                        """)
                .param("ids", ids.toArray(String[]::new))
                .query((rs, _) -> Map.entry(rs.getString("duplicate_of"), rs.getInt("n")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Override
    public void append(HistoryEntry h) {
        var params = new HashMap<String, @Nullable Object>();
        params.put("id", h.id());
        params.put("feedback", h.feedbackId());
        params.put("from", h.from() == null ? null : h.from().code());
        params.put("to", h.to().code());
        params.put("blocking", h.blocking());
        params.put("actor", h.actorId());
        params.put("note", h.note());
        params.put("at", ts(h.at()));
        jdbc.sql("""
                        insert into uat.feedback_history (id, feedback_id, from_state, to_state, blocking, actor_id,
                            note, at)
                        values (:id, :feedback, :from, :to, :blocking, :actor, :note, :at)
                        """).params(params).update();
    }

    @Override
    public List<HistoryEntry> history(String feedbackId) {
        return jdbc.sql("select * from uat.feedback_history where feedback_id = :id order by at, id")
                .param("id", feedbackId)
                .query(UatJdbc::history)
                .list();
    }

    @Override
    public List<HistoryEntry> allHistory() {
        return jdbc.sql("select * from uat.feedback_history order by at, id")
                .query(UatJdbc::history)
                .list();
    }

    @Override
    public List<Instant> reportedSince(Instant since) {
        return jdbc.sql("select created_at from uat.feedback where created_at >= :since")
                .param("since", ts(since))
                .query((rs, _) -> requiredInstant(rs, "created_at"))
                .list();
    }

    // scripts and sign-offs

    @Override
    public List<Script> scripts() {
        return jdbc.sql("select * from uat.scripts order by sort")
                .query((rs, _) -> {
                    var titles = json.readTree(rs.getString("title_i18n"));
                    return new Script(
                            rs.getString("code"),
                            CodedEnum.fromCode(Persona.class, rs.getString("persona")),
                            rs.getString("version"),
                            titles.path("en").asString(),
                            titles.path("fr").asString(),
                            rs.getString("doc_path"));
                })
                .list();
    }

    @Override
    public void insert(Signoff s) {
        var params = new HashMap<String, @Nullable Object>();
        params.put("id", s.id());
        params.put("participant", s.participantId());
        params.put("script", s.scriptCode());
        params.put("version", s.scriptVersion());
        params.put("outcome", s.outcome().code());
        params.put("comments", s.comments());
        params.put("blocking", s.blockingIds().toArray(String[]::new));
        params.put("by", s.recordedBy());
        params.put("at", ts(s.recordedAt()));
        jdbc.sql("""
                        insert into uat.signoffs (id, participant_id, script_code, script_version, outcome, comments,
                            blocking_ids, recorded_by, recorded_at)
                        values (:id, :participant, :script, :version, :outcome, :comments, :blocking, :by, :at)
                        """).params(params).update();
    }

    @Override
    public List<Signoff> latestSignoffs() {
        return jdbc.sql("""
                        select distinct on (participant_id, script_code) * from uat.signoffs
                         order by participant_id, script_code, recorded_at desc, id desc
                        """).query(UatJdbc::signoff).list();
    }

    @Override
    public List<Signoff> signoffsOf(String participantId) {
        return jdbc.sql("select * from uat.signoffs where participant_id = :id order by recorded_at desc")
                .param("id", participantId)
                .query(UatJdbc::signoff)
                .list();
    }

    // rows

    private static Participant participant(ResultSet rs, int row) throws SQLException {
        return new Participant(
                rs.getString("id"),
                rs.getString("user_id"),
                null,
                CodedEnum.fromCode(Persona.class, rs.getString("persona")),
                rs.getString("label"),
                rs.getBoolean("active"),
                rs.getString("added_by"),
                requiredInstant(rs, "created_at"));
    }

    private static Screenshot screenshot(ResultSet rs, int row) throws SQLException {
        return new Screenshot(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("storage_key"),
                rs.getString("content_type"),
                rs.getInt("byte_size"),
                requiredInstant(rs, "created_at"));
    }

    private static Feedback feedback(ResultSet rs, int row) throws SQLException {
        var bytes = rs.getInt("screenshot_bytes");
        var hasBytes = !rs.wasNull();
        var blocking = rs.getBoolean("blocking");
        var hasBlocking = !rs.wasNull();
        return new Feedback(
                rs.getString("id"),
                rs.getLong("number"),
                rs.getString("participant_id"),
                CodedEnum.fromCode(Persona.class, rs.getString("persona")),
                rs.getString("label"),
                rs.getString("user_id"),
                rs.getString("merchant_id"),
                CodedEnum.fromCode(FeedbackApp.class, rs.getString("app")),
                CodedEnum.fromCode(FeedbackCategory.class, rs.getString("category")),
                CodedEnum.fromCode(Severity.class, rs.getString("severity")),
                rs.getString("body"),
                rs.getString("route"),
                rs.getString("app_version"),
                rs.getString("locale"),
                rs.getString("platform"),
                rs.getString("screenshot_key"),
                rs.getString("screenshot_type"),
                hasBytes ? bytes : null,
                CodedEnum.fromCode(FeedbackState.class, rs.getString("state")),
                hasBlocking ? blocking : null,
                rs.getString("owner_id"),
                rs.getString("tracker_url"),
                rs.getString("duplicate_of"),
                requiredInstant(rs, "created_at"),
                requiredInstant(rs, "updated_at"),
                rs.getInt("version"));
    }

    private static HistoryEntry history(ResultSet rs, int row) throws SQLException {
        var from = rs.getString("from_state");
        var blocking = rs.getBoolean("blocking");
        var hasBlocking = !rs.wasNull();
        return new HistoryEntry(
                rs.getString("id"),
                rs.getString("feedback_id"),
                from == null ? null : CodedEnum.fromCode(FeedbackState.class, from),
                CodedEnum.fromCode(FeedbackState.class, rs.getString("to_state")),
                hasBlocking ? blocking : null,
                rs.getString("actor_id"),
                rs.getString("note"),
                requiredInstant(rs, "at"));
    }

    private static Signoff signoff(ResultSet rs, int row) throws SQLException {
        var ids = rs.getArray("blocking_ids");
        return new Signoff(
                rs.getString("id"),
                rs.getString("participant_id"),
                rs.getString("script_code"),
                rs.getString("script_version"),
                CodedEnum.fromCode(SignoffOutcome.class, rs.getString("outcome")),
                rs.getString("comments"),
                ids == null ? List.of() : Arrays.asList((String[]) ids.getArray()),
                rs.getString("recorded_by"),
                requiredInstant(rs, "recorded_at"));
    }
}
