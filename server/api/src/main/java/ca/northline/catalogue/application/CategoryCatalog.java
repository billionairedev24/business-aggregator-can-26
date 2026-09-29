package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CategoryProfile;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Outbound port: categories with their attribute templates, banned flag and median approved price. */
public interface CategoryCatalog {

    /** Every category under {@code root} (groups and leaves), names in {@code locale}, parents before children. */
    List<CategoryProfile> all(String root, Locale locale);

    Optional<CategoryProfile> profile(String categoryId);
}
