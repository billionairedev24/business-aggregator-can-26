package ca.northline.studio.application;

import java.util.Locale;

/** {@code GET /api/v1/merchants/{id}/dashboard}. */
public interface ViewDashboard {
    Dashboard view(String merchantId, String viewerUserId, Locale locale);
}
