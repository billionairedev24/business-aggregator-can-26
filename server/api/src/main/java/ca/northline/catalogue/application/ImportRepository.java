package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.ImportBatch;
import java.util.List;
import java.util.Optional;

/** Outbound port for {@code catalogue.imports}. */
public interface ImportRepository {

    void insert(ImportBatch batch);

    void update(ImportBatch batch);

    Optional<ImportBatch> find(String merchantId, String importId);

    /** Newest first. */
    List<ImportBatch> history(String merchantId, int limit);
}
