package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.ImportBatch;
import ca.northline.catalogue.domain.ImportTemplate;
import java.util.List;

/** Bulk upload: validate a spreadsheet (nothing changes yet), then import its valid rows; plus the upload history. */
public interface BulkImport {

    ImportBatch validate(String merchantId, ImportTemplate template, String fileName, byte[] bytes, String actorId);

    ImportBatch commit(String merchantId, String importId, String actorId);

    List<ImportBatch> history(String merchantId);
}
