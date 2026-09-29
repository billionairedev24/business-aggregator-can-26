package ca.northline.merchants.application;

import ca.northline.shared.security.MerchantRole;

/** The team member making a Settings or Compliance change (for the audit log and events). */
public record SettingsActor(String merchantId, String userId, MerchantRole role) {}
