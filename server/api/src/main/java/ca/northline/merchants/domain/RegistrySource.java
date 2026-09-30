package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.registry_checks.source}: who is asked (docs/runbooks/registries.md). */
public enum RegistrySource implements CodedEnum {
    /** ISED Federal Corporation API (CBCA, NFP Act, Coop Act corporations). */
    CORPORATIONS_CANADA,
    /** Alberta Corporate Registry — no public API: a search service, or a registry-agent search done by an agent. */
    ALBERTA_CORPORATE_REGISTRY,
    /** City of Calgary business licences (Open Calgary, Socrata dataset vdjc-pybd). */
    CALGARY_BUSINESS_LICENCES,
    /** Regulators without an API (AMVIC, AHS, AGLC, RECA, Safety Codes, …): a Northline agent checks. */
    MANUAL
}
