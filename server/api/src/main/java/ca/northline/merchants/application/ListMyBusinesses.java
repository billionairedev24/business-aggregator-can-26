package ca.northline.merchants.application;

import java.util.List;

/** Businesses the user belongs to, oldest first — drives the Studio "Switch business" menu. */
public interface ListMyBusinesses {
    List<BusinessSummary> of(String userId);
}
