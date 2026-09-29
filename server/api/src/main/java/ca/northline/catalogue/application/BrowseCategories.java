package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CategoryProfile;
import java.util.List;
import java.util.Locale;

/** Categories under a root (service | shop) for the cascading category dropdowns. */
public interface BrowseCategories {
    List<CategoryProfile> categories(String root, Locale locale);
}
