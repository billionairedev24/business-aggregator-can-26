package ca.northline.catalogue.application;

/** Outbound port: does a merchant hold a verified, unexpired licence from a registry (AMVIC, AGLC, RECA …)? */
public interface LicenceRegistry {
    boolean hasVerifiedLicence(String merchantId, String registry);
}
