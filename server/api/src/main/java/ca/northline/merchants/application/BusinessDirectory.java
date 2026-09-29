package ca.northline.merchants.application;

import java.util.List;

/** Outbound port: membership read model (merchant_members ⋈ merchants). */
public interface BusinessDirectory {
    List<BusinessSummary> businessesOf(String userId);
}
