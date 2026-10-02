# Attestation of Compliance — SAQ A (placeholder)

**Not signed. Placeholder for the PCI DSS v4.0.1 Attestation of Compliance for SAQ A.** The official AOC form comes
from the PCI SSC document library (or Stripe's dashboard PCI page); fill it from [saq-a.md](saq-a.md) and keep the
signed PDF next to this file (`aoc-YYYY.pdf`). Renew it every year and after a significant change to the payment
flow (a new payment page, a new script origin, a new PSP).

## Part 1 — merchant and assessment information

| field | value |
|---|---|
| Company name | Northline Marketplace Inc. (`NL_LEGAL_ENTITY`) — confirm the registered name |
| DBA | Northline |
| Contact (security lead) | *to be named* |
| Business address | the registered office (the Terms' address, `EMAIL_MAILING_ADDRESS`) — confirm |
| URL | the consumer site's public origin (`NL_SITE_ORIGIN` in prod) |
| Payment channels | e-commerce only |
| Services / products | an online marketplace for services, shop goods and food (Northline is merchant of record) |
| Payment processor / TPSP | Stripe Payments Canada, Ltd. (Stripe, Inc.) — confirm the contracting entity on the Services Agreement |
| Assessment type | SAQ A v4.0.1, self-assessment, no QSA / ISA |
| Date of assessment | *at attestation* |

## Part 2 — executive summary

- Payment channels and how cards are accepted: Stripe Payment Element (iframe) on the website, Stripe PaymentSheet in
  the apps; Northline never receives, stores or transmits account data ([cardholder-data-flow.md](cardholder-data-flow.md)).
- Locations: none with card data. Hosting: one Canadian region of the chosen cloud.
- Third-party service providers: [saq-a.md § 12.8.1](saq-a.md#requirement-12--policies-and-third-party-service-providers).
- Eligibility: [saq-a.md § Eligibility criteria](saq-a.md#eligibility-criteria).

## Part 3 — validation

| field | value |
|---|---|
| Compliance status | *Compliant* only when every requirement is In Place or Not Applicable; otherwise *Non-Compliant* with a target date (Part 4) |
| Merchant executive officer (signs 3b) | *name, title, signature, date* |
| QSA / ISA involvement (3c, 3d) | none |

## Part 4 — action plan for non-compliant requirements

The "not yet" table of [saq-a.md](saq-a.md#not-yet-items-and-owners), with a target date per item, if the AOC is
submitted before they are closed.

## Records kept with this file

- Stripe's PCI DSS AOC (service provider), current year — *not obtained: no Stripe account yet*.
- The cloud provider's PCI DSS AOC for the region used — *not obtained*.
- ASV scan reports (quarterly) — *none yet*.
- The output of `make pci-scan` for the release attested.
