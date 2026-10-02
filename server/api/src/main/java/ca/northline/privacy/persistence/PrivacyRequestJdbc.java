package ca.northline.privacy.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.privacy.application.PrivacyRequestStore;
import ca.northline.privacy.domain.Decision;
import ca.northline.privacy.domain.ExtensionReason;
import ca.northline.privacy.domain.RequestState;
import ca.northline.privacy.domain.RequestType;
import ca.northline.privacy.domain.SubjectKind;
import ca.northline.privacy.domain.Verification;
import ca.northline.region.api.PrivacyLaw;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link PrivacyRequestStore} over {@code privacy.requests} and {@code privacy.erasure_steps} with JdbcClient. */
@Repository
@RequiredArgsConstructor
class PrivacyRequestJdbc implements PrivacyRequestStore {

    private static final TypeReference<List<Kept>> KEPT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    @Override
    public Request insert(Request r) {
        var number = jdbc.sql("""
                        insert into privacy.requests (id, subject_id, subject_kind, merchant_ids, type, state, channel,
                            province, law, received_at, due_at, verification, verified_at, verified_by, code_hash,
                            code_expires_at, scheduled_for, created_by, sealed_key_ref, sealed_key, sealed_data)
                        values (:id, :subject, :kind, :merchants, :type, :state, :channel, :province, :law, :received,
                            :due, :verification, :verifiedAt, :verifiedBy, :codeHash, :codeExpires, :scheduled,
                            :createdBy, :keyRef, :key, :data)
                        returning number
                        """)
                .params(Map.of(
                        "id", r.id(),
                        "subject", r.subjectId(),
                        "kind", r.subjectKind().code(),
                        "merchants", r.merchantIds().toArray(String[]::new),
                        "type", r.type().code(),
                        "state", r.state().code(),
                        "channel", r.channel(),
                        "province", r.province(),
                        "law", r.law().code(),
                        "received", ts(r.receivedAt())))
                .params(nullable(r))
                .query(Long.class)
                .single();
        return r.withNumber(number);
    }

    /** Columns that may be null (Map.of refuses nulls). */
    private static Map<String, @Nullable Object> nullable(Request r) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("due", ts(r.dueAt()));
        p.put("verification", code(r.verification()));
        p.put("verifiedAt", ts(r.verifiedAt()));
        p.put("verifiedBy", r.verifiedBy());
        p.put("codeHash", r.codeHash());
        p.put("codeExpires", ts(r.codeExpiresAt()));
        p.put("scheduled", ts(r.scheduledFor()));
        p.put("createdBy", r.createdBy());
        var sealed = r.sealed();
        p.put("keyRef", sealed == null ? null : sealed.keyRef());
        p.put("key", sealed == null ? null : sealed.wrappedKey());
        p.put("data", sealed == null ? null : sealed.ciphertext());
        return p;
    }

    private static final String SELECT = "select * from privacy.requests ";

    @Override
    public Optional<Request> find(String id) {
        return jdbc.sql(SELECT + "where id = :id")
                .param("id", id)
                .query(this::request)
                .optional();
    }

    @Override
    public Optional<Request> lock(String id) {
        return jdbc.sql(SELECT + "where id = :id for update")
                .param("id", id)
                .query(this::request)
                .optional();
    }

    @Override
    public boolean save(Request r) {
        var p = nullable(r);
        p.put("id", r.id());
        p.put("version", r.version());
        p.put("state", r.state().code());
        p.put("extendedTo", ts(r.extendedTo()));
        p.put("extensionReason", code(r.extensionReason()));
        p.put("codeAttempts", r.codeAttempts());
        p.put("started", ts(r.startedAt()));
        p.put("completed", ts(r.completedAt()));
        p.put("decision", code(r.decision()));
        p.put("decisionNote", r.decisionNote());
        p.put("decidedBy", r.decidedBy());
        p.put("exportKey", r.exportKey());
        p.put("exportBytes", r.exportBytes());
        p.put("exportExpires", ts(r.exportExpiresAt()));
        p.put("linkHash", r.linkHash());
        p.put("linkExpires", ts(r.linkExpiresAt()));
        p.put("holds", r.holdsOpen());
        return jdbc.sql("""
                                update privacy.requests
                                   set state = :state, due_at = :due, extended_to = :extendedTo,
                                       extension_reason = :extensionReason, verification = :verification,
                                       verified_at = :verifiedAt, verified_by = :verifiedBy, code_hash = :codeHash,
                                       code_expires_at = :codeExpires, code_attempts = :codeAttempts,
                                       scheduled_for = :scheduled, started_at = :started, completed_at = :completed,
                                       decision = :decision, decision_note = :decisionNote, decided_by = :decidedBy,
                                       sealed_key_ref = :keyRef, sealed_key = :key, sealed_data = :data,
                                       export_key = :exportKey, export_bytes = :exportBytes,
                                       export_expires_at = :exportExpires, link_hash = :linkHash,
                                       link_expires_at = :linkExpires, holds_open = :holds, updated_at = now(),
                                       version = version + 1
                                 where id = :id and version = :version
                                """).params(p).update() == 1;
    }

    @Override
    public int textsSince(String subjectId, Instant since) {
        return jdbc.sql("select count(*) from privacy.verification_texts where subject_id = :s and sent_at >= :since")
                .param("s", subjectId)
                .param("since", ts(since))
                .query(Integer.class)
                .single();
    }

    @Override
    public void textSent(String subjectId, String requestId, Instant at) {
        jdbc.sql("delete from privacy.verification_texts where sent_at < :old")
                .param("old", ts(at.minus(Duration.ofDays(2))))
                .update();
        jdbc.sql("""
                        insert into privacy.verification_texts (id, subject_id, request_id, sent_at)
                        values (:id, :s, :r, :at)
                        """)
                .param("id", Ids.next())
                .param("s", subjectId)
                .param("r", requestId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public boolean hasOpen(String subjectId, RequestType type) {
        return jdbc.sql("""
                        select exists (select 1 from privacy.requests where subject_id = :s and type = :t
                                          and state in ('awaiting_verification', 'verified', 'in_progress'))
                        """)
                .param("s", subjectId)
                .param("t", type.code())
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<Request> of(String subjectId) {
        return jdbc.sql(SELECT + "where subject_id = :s order by received_at desc, id desc limit 50")
                .param("s", subjectId)
                .query(this::request)
                .list();
    }

    @Override
    public List<Request> queue(Set<RequestState> states, @Nullable RequestType type, int limit) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("states", states.stream().map(CodedEnum::code).toList());
        p.put("type", code(type));
        p.put("limit", limit);
        return jdbc.sql(SELECT + """
                        where state in (:states) and (cast(:type as text) is null or type = :type)
                        order by coalesce(extended_to, due_at), received_at limit :limit
                        """).params(p).query(this::request).list();
    }

    @Override
    public List<String> due(Instant now, int limit) {
        return jdbc.sql("""
                        select id from privacy.requests
                         where type in ('access', 'erasure')
                           and ((state = 'verified' and scheduled_for <= :now) or state = 'in_progress')
                         order by scheduled_for nulls last limit :limit
                        """)
                .param("now", ts(now))
                .param("limit", limit)
                .query((rs, _) -> rs.getString(1))
                .list();
    }

    @Override
    public List<String> withDueSteps(Instant now, int limit) {
        return jdbc.sql("""
                        select distinct s.request_id from privacy.erasure_steps s
                          join privacy.requests r on r.id = s.request_id
                         where s.status in ('pending', 'failed', 'held') and s.next_attempt_at <= :now
                           and r.state in ('in_progress', 'completed')
                         limit :limit
                        """)
                .param("now", ts(now))
                .param("limit", limit)
                .query((rs, _) -> rs.getString(1))
                .list();
    }

    @Override
    public List<Request> expiredExports(Instant now, int limit) {
        return jdbc.sql(SELECT + "where export_key is not null and export_expires_at < :now limit :limit")
                .param("now", ts(now))
                .param("limit", limit)
                .query(this::request)
                .list();
    }

    @Override
    public Optional<Request> byLink(String linkHash) {
        return jdbc.sql(SELECT + "where link_hash = :h")
                .param("h", linkHash)
                .query(this::request)
                .optional();
    }

    @Override
    public void createSteps(String requestId, Collection<StepKey> steps, Instant now) {
        for (var step : steps) {
            jdbc.sql("""
                            insert into privacy.erasure_steps (request_id, module, sort, status, next_attempt_at)
                            values (:r, :m, :sort, 'pending', :now) on conflict do nothing
                            """)
                    .param("r", requestId)
                    .param("m", step.module())
                    .param("sort", step.sort())
                    .param("now", ts(now))
                    .update();
        }
    }

    @Override
    public List<Step> steps(String requestId) {
        return jdbc.sql("select * from privacy.erasure_steps where request_id = :r order by sort, module")
                .param("r", requestId)
                .query(this::step)
                .list();
    }

    @Override
    public Optional<Step> lockStep(String requestId, String module) {
        return jdbc.sql("""
                        select * from privacy.erasure_steps where request_id = :r and module = :m
                           for update skip locked
                        """)
                .param("r", requestId)
                .param("m", module)
                .query(this::step)
                .optional();
    }

    @Override
    public void saveStep(Step s) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("r", s.requestId());
        p.put("m", s.module());
        p.put("status", s.status());
        p.put("attempts", s.attempts());
        p.put("error", s.lastError());
        p.put("holds", json.writeValueAsString(s.holds()));
        p.put("retained", json.writeValueAsString(s.retained()));
        p.put("next", ts(s.nextAttemptAt()));
        p.put("done", ts(s.doneAt()));
        jdbc.sql("""
                        update privacy.erasure_steps
                           set status = :status, attempts = :attempts, last_error = :error,
                               holds = cast(:holds as jsonb), retained = cast(:retained as jsonb),
                               next_attempt_at = :next, done_at = :done, updated_at = now()
                         where request_id = :r and module = :m
                        """).params(p).update();
    }

    private Request request(ResultSet rs, int row) throws SQLException {
        var keyRef = rs.getString("sealed_key_ref");
        var key = rs.getBytes("sealed_key");
        var data = rs.getBytes("sealed_data");
        var exportBytes = rs.getLong("export_bytes");
        var exportBytesNull = rs.wasNull();
        return new Request(
                rs.getString("id"),
                rs.getLong("number"),
                rs.getString("subject_id"),
                CodedEnum.fromCode(SubjectKind.class, rs.getString("subject_kind")),
                strings(rs.getArray("merchant_ids")),
                CodedEnum.fromCode(RequestType.class, rs.getString("type")),
                CodedEnum.fromCode(RequestState.class, rs.getString("state")),
                rs.getString("channel"),
                rs.getString("province"),
                CodedEnum.fromCode(PrivacyLaw.class, rs.getString("law")),
                requiredInstant(rs, "received_at"),
                requiredInstant(rs, "due_at"),
                instant(rs, "extended_to"),
                enumOf(ExtensionReason.class, rs.getString("extension_reason")),
                enumOf(Verification.class, rs.getString("verification")),
                instant(rs, "verified_at"),
                rs.getString("verified_by"),
                rs.getString("code_hash"),
                instant(rs, "code_expires_at"),
                rs.getInt("code_attempts"),
                instant(rs, "scheduled_for"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                enumOf(Decision.class, rs.getString("decision")),
                rs.getString("decision_note"),
                rs.getString("decided_by"),
                rs.getString("created_by"),
                keyRef == null || key == null || data == null ? null : new Sealed(keyRef, key, data),
                rs.getString("export_key"),
                exportBytesNull ? null : exportBytes,
                instant(rs, "export_expires_at"),
                rs.getString("link_hash"),
                instant(rs, "link_expires_at"),
                rs.getInt("holds_open"),
                rs.getInt("version"));
    }

    private Step step(ResultSet rs, int row) throws SQLException {
        return new Step(
                rs.getString("request_id"),
                rs.getString("module"),
                rs.getInt("sort"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                json.readValue(rs.getString("holds"), KEPT),
                json.readValue(rs.getString("retained"), KEPT),
                instant(rs, "next_attempt_at"),
                instant(rs, "done_at"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static <E extends Enum<E> & CodedEnum> @Nullable E enumOf(Class<E> type, @Nullable String code) {
        return code == null ? null : CodedEnum.fromCode(type, code);
    }

    private static @Nullable String code(@Nullable CodedEnum value) {
        return value == null ? null : value.code();
    }
}
