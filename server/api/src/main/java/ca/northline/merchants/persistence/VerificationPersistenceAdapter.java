package ca.northline.merchants.persistence;

import static ca.northline.merchants.persistence.ApplicationPersistenceAdapter.instant;
import static ca.northline.merchants.persistence.ApplicationPersistenceAdapter.timestamp;

import ca.northline.merchants.application.VerificationRepository;
import ca.northline.merchants.domain.CheckType;
import ca.northline.merchants.domain.Verification;
import ca.northline.merchants.domain.VerificationStatus;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class VerificationPersistenceAdapter implements VerificationRepository {

    private static final String COLUMNS = """
            select id, merchant_id, check_key, check_type, registry, status, reference, document_media_id, expires_at,
                   position, updated_at
              from merchants.verifications
            """;

    private final JdbcClient jdbc;

    @Override
    public List<Verification> listFor(String merchantId) {
        return jdbc.sql(COLUMNS + " where merchant_id = :m and check_key is not null order by position, created_at")
                .param("m", merchantId)
                .query((rs, _) -> toDomain(rs))
                .list();
    }

    @Override
    public Optional<Verification> find(String merchantId, String verificationId) {
        return jdbc.sql(COLUMNS + " where merchant_id = :m and id = :id and check_key is not null")
                .param("m", merchantId)
                .param("id", verificationId)
                .query((rs, _) -> toDomain(rs))
                .optional();
    }

    @Override
    public void replace(String merchantId, List<Verification> checklist) {
        var keep = checklist.stream().map(Verification::getId).toList();
        var stale = jdbc
                .sql("select id from merchants.verifications where merchant_id = :m and check_key is not null")
                .param("m", merchantId)
                .query(String.class)
                .list()
                .stream()
                .filter(id -> !keep.contains(id))
                .toList();
        if (!stale.isEmpty()) {
            jdbc.sql(
                            "update merchants.merchant_principals set kyc_verification_id = null where kyc_verification_id in (:ids)")
                    .param("ids", stale)
                    .update();
            jdbc.sql("delete from merchants.verifications where id in (:ids)")
                    .param("ids", stale)
                    .update();
        }
        checklist.forEach(this::save);
    }

    @Override
    public void save(Verification v) {
        jdbc.sql("""
                        insert into merchants.verifications (id, merchant_id, check_key, check_type, registry, status,
                               reference, document_media_id, expires_at, position, updated_at)
                        values (:id, :m, :key, :type, :registry, :status, :reference, :doc, :expires, :position, :updated)
                        on conflict (id) do update set status = excluded.status, reference = excluded.reference,
                               document_media_id = excluded.document_media_id, expires_at = excluded.expires_at,
                               position = excluded.position, updated_at = excluded.updated_at
                        """)
                .param("id", v.getId())
                .param("m", v.getMerchantId())
                .param("key", v.getKey())
                .param("type", v.getType().code())
                .param("registry", v.getRegistry())
                .param("status", v.getStatus().code())
                .param("reference", v.getReference())
                .param("doc", v.getDocumentId())
                .param("expires", timestamp(v.getExpiresAt()))
                .param("position", v.getPosition())
                .param("updated", timestamp(v.getUpdatedAt()))
                .update();
    }

    private static Verification toDomain(ResultSet rs) throws SQLException {
        return Verification.builder()
                .id(rs.getString("id"))
                .merchantId(rs.getString("merchant_id"))
                .key(rs.getString("check_key"))
                .type(CodedEnum.fromCode(CheckType.class, rs.getString("check_type")))
                .registry(rs.getString("registry"))
                .status(CodedEnum.fromCode(VerificationStatus.class, rs.getString("status")))
                .reference(rs.getString("reference"))
                .documentId(rs.getString("document_media_id"))
                .expiresAt(instant(rs, "expires_at"))
                .position(rs.getInt("position"))
                .updatedAt(Objects.requireNonNull(instant(rs, "updated_at")))
                .build();
    }
}
