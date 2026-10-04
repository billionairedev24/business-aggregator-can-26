package ca.northline.catalogue.application;

import ca.northline.catalogue.api.TaxonomyAdmin.Category;
import ca.northline.catalogue.api.TaxonomyAdmin.Regulator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** {@code catalogue.categories}, {@code catalogue.regulators} and {@code catalogue.category_regulators} (S-94). */
public interface TaxonomyStore {

    List<Category> categories();

    Optional<Category> category(String id);

    /** A new category row (names in {@code name_i18n} and the French label), marked as edited in the console. */
    void insert(Row row, String actorId, Instant at);

    /** The names, booking type, licence registry and vulnerable-sector check of an existing row. */
    void update(Row row, String actorId, Instant at);

    /**
     * {@code clear}: the province falls back to the default registry (the row goes); else {@code regulator} null = not
     * regulated there.
     */
    void regulate(
            String categoryId, String province, boolean clear, @Nullable String regulator, String actorId, Instant at);

    void classify(String categoryId, @Nullable String ageClass, String actorId, Instant at);

    List<Regulator> regulators();

    Optional<Regulator> regulator(String code);

    void insertRegulator(Regulator regulator, String actorId);

    void updateRegulator(Regulator regulator, String actorId);

    record Row(
            String id,
            @Nullable String parentId,
            String root,
            String nameEn,
            @Nullable String nameFr,
            @Nullable String bookingType,
            @Nullable String regulatedRegistry,
            boolean requiresVsCheck) {}
}
