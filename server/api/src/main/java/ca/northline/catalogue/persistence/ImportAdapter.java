package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.*;

import ca.northline.catalogue.application.ImportRepository;
import ca.northline.catalogue.domain.ImportBatch;
import ca.northline.catalogue.domain.ImportBatch.RowError;
import ca.northline.catalogue.domain.ImportBatch.ValidRow;
import ca.northline.catalogue.domain.ImportTemplate;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code catalogue.imports}: errors and pending rows as jsonb. */
@Repository
@RequiredArgsConstructor
class ImportAdapter implements ImportRepository {

    private final JdbcClient jdbc;

    @Override
    public void insert(ImportBatch b) {
        jdbc.sql("""
                        insert into catalogue.imports (id, merchant_id, file_name, template, row_count, create_count,
                          update_count, error_count, errors, pending_rows, status, created_by, created_at, imported_at)
                        values (:id, :m, :file, :template, :rows, :creates, :updates, :errorCount, cast(:errors as jsonb),
                          cast(:pending as jsonb), :status, :by, :at, :importedAt)
                        """)
                .param("id", b.id())
                .param("m", b.merchantId())
                .param("file", b.fileName())
                .param("template", b.template().code())
                .param("rows", b.rowCount())
                .param("creates", b.createCount())
                .param("updates", b.updateCount())
                .param("errorCount", b.errors().size())
                .param("errors", json(b.errors()))
                .param("pending", json(b.pendingRows()))
                .param("status", b.status().code())
                .param("by", b.createdBy())
                .param("at", ts(b.createdAt()))
                .param("importedAt", ts(b.importedAt()))
                .update();
    }

    @Override
    public void update(ImportBatch b) {
        jdbc.sql("""
                        update catalogue.imports set status = :status, pending_rows = cast(:pending as jsonb),
                          imported_at = :importedAt where id = :id
                        """)
                .param("status", b.status().code())
                .param("pending", json(b.pendingRows()))
                .param("importedAt", ts(b.importedAt()))
                .param("id", b.id())
                .update();
    }

    @Override
    public Optional<ImportBatch> find(String merchantId, String importId) {
        return jdbc.sql("select * from catalogue.imports where id = :id and merchant_id = :m")
                .param("id", importId)
                .param("m", merchantId)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public List<ImportBatch> history(String merchantId, int limit) {
        return jdbc.sql("select * from catalogue.imports where merchant_id = :m order by created_at desc limit :limit")
                .param("m", merchantId)
                .param("limit", limit)
                .query((rs, _) -> map(rs))
                .list();
    }

    private static ImportBatch map(ResultSet rs) throws SQLException {
        return new ImportBatch(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("file_name"),
                CodedEnum.fromCode(ImportTemplate.class, rs.getString("template")),
                rs.getInt("row_count"),
                rs.getInt("create_count"),
                rs.getInt("update_count"),
                list(rs.getString("errors"), RowError.class),
                list(rs.getString("pending_rows"), ValidRow.class),
                CodedEnum.fromCode(ImportBatch.Status.class, rs.getString("status")),
                rs.getString("created_by"),
                requiredInstant(rs, "created_at"),
                instant(rs, "imported_at"));
    }
}
