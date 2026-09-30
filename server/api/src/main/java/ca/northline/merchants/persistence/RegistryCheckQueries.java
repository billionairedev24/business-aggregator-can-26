package ca.northline.merchants.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.merchants.application.RegistryCheckStore;
import ca.northline.merchants.domain.RegistryCheck;
import ca.northline.merchants.domain.RegistryOutcome;
import ca.northline.merchants.domain.RegistrySource;
import ca.northline.merchants.domain.RegistrySubject;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link RegistryCheckStore} over {@code merchants.registry_checks} (V033) and {@code verifications.rechecked_at}. */
@Repository
@RequiredArgsConstructor
class RegistryCheckQueries implements RegistryCheckStore {

    private static final String COLUMNS = """
            select id, merchant_id, verification_id, source, subject, registry, query_number, expected_name, trigger,
                   outcome, reasons, record_name, record_number, record_status, record_expires_on, reference,
                   checked_at, review_state, reviewed_by, reviewed_at, review_note
              from merchants.registry_checks
            """;

    private final JdbcClient jdbc;

    @Override
    public void insert(RegistryCheck c) {
        jdbc.sql("""
                        insert into merchants.registry_checks (id, merchant_id, verification_id, source, subject,
                               registry, query_number, expected_name, trigger, outcome, reasons, record_name,
                               record_number, record_status, record_expires_on, reference, checked_at, review_state)
                        values (:id, :m, :v, :source, :subject, :registry, :number, :expected, :trigger, :outcome,
                                cast(:reasons as text[]), :recordName, :recordNumber, :recordStatus, :expiresOn, :reference, :at,
                                :review)
                        """)
                .param("id", c.getId())
                .param("m", c.getMerchantId())
                .param("v", c.getVerificationId())
                .param("source", c.getSource().code())
                .param("subject", c.getSubject().code())
                .param("registry", c.getRegistry())
                .param("number", c.getQueryNumber())
                .param("expected", c.getExpectedName())
                .param("trigger", c.getTrigger().code())
                .param("outcome", c.getOutcome().code())
                .param("reasons", textArray(c.getReasons()))
                .param("recordName", c.getRecordName())
                .param("recordNumber", c.getRecordNumber())
                .param("recordStatus", c.getRecordStatus())
                .param("expiresOn", c.getRecordExpiresOn())
                .param("reference", c.getReference())
                .param("at", ts(c.getCheckedAt()))
                .param("review", CodedEnums.toCode(c.getReviewState()))
                .update();
    }

    @Override
    public void saveReview(RegistryCheck c) {
        jdbc.sql("""
                        update merchants.registry_checks
                           set review_state = :state, reviewed_by = :by, reviewed_at = :at, review_note = :note
                         where id = :id
                        """)
                .param("id", c.getId())
                .param("state", CodedEnums.toCode(c.getReviewState()))
                .param("by", c.getReviewedBy())
                .param("at", ts(c.getReviewedAt()))
                .param("note", c.getReviewNote())
                .update();
    }

    @Override
    public Optional<RegistryCheck> lock(String checkId) {
        return jdbc.sql(COLUMNS + " where id = :id for update")
                .param("id", checkId)
                .query((rs, _) -> check(rs))
                .optional();
    }

    @Override
    public List<RegistryCheck> openReviews(int limit) {
        return jdbc.sql(COLUMNS + " where review_state = 'open' order by checked_at, id limit :n")
                .param("n", limit)
                .query((rs, _) -> check(rs))
                .list();
    }

    @Override
    public boolean hasOpenReview(String verificationId) {
        return jdbc.sql("""
                        select exists(select 1 from merchants.registry_checks
                                       where verification_id = :v and review_state = 'open')""").param("v", verificationId).query(Boolean.class).single();
    }

    @Override
    public List<RegistryCheck> latest(String verificationId, int limit) {
        return jdbc.sql(COLUMNS + " where verification_id = :v order by checked_at desc, id desc limit :n")
                .param("v", verificationId)
                .param("n", limit)
                .query((rs, _) -> check(rs))
                .list();
    }

    @Override
    public List<Due> dueForRecheck(Instant before, int limit) {
        return jdbc.sql("""
                        select v.merchant_id, v.id from merchants.verifications v
                         where v.status = 'verified' and v.check_key is not null
                           and (v.rechecked_at is null or v.rechecked_at < :before)
                           and exists (select 1 from merchants.registry_checks c
                                        where c.verification_id = v.id and c.source <> 'manual')
                         order by v.rechecked_at nulls first, v.id
                         limit :n
                           for update of v skip locked
                        """)
                .param("before", ts(before))
                .param("n", limit)
                .query((rs, _) -> new Due(rs.getString("merchant_id"), rs.getString("id")))
                .list();
    }

    @Override
    public void markRechecked(String verificationId, Instant at) {
        jdbc.sql("update merchants.verifications set rechecked_at = :at where id = :id")
                .param("id", verificationId)
                .param("at", ts(at))
                .update();
    }

    private static RegistryCheck check(ResultSet rs) throws SQLException {
        var expires = rs.getObject("record_expires_on", LocalDate.class);
        return RegistryCheck.builder()
                .id(rs.getString("id"))
                .merchantId(rs.getString("merchant_id"))
                .verificationId(rs.getString("verification_id"))
                .source(CodedEnum.fromCode(RegistrySource.class, rs.getString("source")))
                .subject(CodedEnum.fromCode(RegistrySubject.class, rs.getString("subject")))
                .registry(rs.getString("registry"))
                .queryNumber(rs.getString("query_number"))
                .expectedName(rs.getString("expected_name"))
                .trigger(CodedEnum.fromCode(RegistryCheck.Trigger.class, rs.getString("trigger")))
                .outcome(CodedEnum.fromCode(RegistryOutcome.class, rs.getString("outcome")))
                .reasons(strings(rs.getArray("reasons")))
                .recordName(rs.getString("record_name"))
                .recordNumber(rs.getString("record_number"))
                .recordStatus(rs.getString("record_status"))
                .recordExpiresOn(expires)
                .reference(rs.getString("reference"))
                .checkedAt(requiredInstant(rs, "checked_at"))
                .reviewState(CodedEnums.fromCode(rs.getString("review_state"), RegistryCheck.ReviewState.class))
                .reviewedBy(rs.getString("reviewed_by"))
                .reviewedAt(instant(rs, "reviewed_at"))
                .reviewNote(rs.getString("review_note"))
                .build();
    }

    /** A Postgres {@code text[]} literal with every element quoted. */
    static String textArray(List<String> values) {
        return values.stream()
                .map(v -> '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"')
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
