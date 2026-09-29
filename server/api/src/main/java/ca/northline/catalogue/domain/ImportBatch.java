package ca.northline.catalogue.domain;

import ca.northline.shared.Conflict;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One uploaded spreadsheet ({@code catalogue.imports}): validated first — nothing changes until the merchant clicks
 * "Import N valid rows" — then imported. Existing SKUs are updated, new ones are created as drafts.
 *
 * @param pendingRows the valid rows, kept until the import is committed
 */
public record ImportBatch(
        String id,
        String merchantId,
        String fileName,
        ImportTemplate template,
        int rowCount,
        int createCount,
        int updateCount,
        List<RowError> errors,
        List<ValidRow> pendingRows,
        Status status,
        @Nullable String createdBy,
        Instant createdAt,
        @Nullable Instant importedAt) {

    public ImportBatch {
        errors = List.copyOf(errors);
        pendingRows = List.copyOf(pendingRows);
    }

    public enum Status implements ca.northline.shared.CodedEnum {
        VALIDATED,
        IMPORTED
    }

    /** A row the merchant must fix and re-upload ({@code row} is the spreadsheet row number, header = 1). */
    public record RowError(int row, @Nullable String sku, String error) {}

    /**
     * A row that passed validation, normalised: {@code values} holds the template columns (lower-case keys), prices in
     * cents as plain digits.
     *
     * @param update true when the SKU already exists (the listing is updated, not created)
     */
    public record ValidRow(int row, String sku, boolean update, Map<String, String> values) {
        public ValidRow {
            values = Map.copyOf(values);
        }
    }

    public int validCount() {
        return createCount + updateCount;
    }

    public ImportBatch imported(Instant at) {
        if (status != Status.VALIDATED) {
            throw new Conflict("import_done", "This file has already been imported.");
        }
        return new ImportBatch(
                id,
                merchantId,
                fileName,
                template,
                rowCount,
                createCount,
                updateCount,
                errors,
                List.of(),
                Status.IMPORTED,
                createdBy,
                createdAt,
                at);
    }
}
