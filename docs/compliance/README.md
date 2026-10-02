# Compliance packets

Material prepared for the people who must sign: the PCI DSS attestation (S-110) and the legal counsel review of the
product's legal texts (S-106). Engineering cannot sign either; these pages say what is done, what isn't, who owns it
and where the evidence is.

| packet | for | pages |
|---|---|---|
| PCI DSS SAQ A | the executive who signs the AOC; Stripe | [saq-a.md](pci/saq-a.md) · [cardholder-data-flow.md](pci/cardholder-data-flow.md) · [stripe-review.md](pci/stripe-review.md) · [payment-page-scripts.md](pci/payment-page-scripts.md) · [aoc.md](pci/aoc.md) |
| Legal review | outside counsel | [review-packet.md](legal/review-packet.md) · [counsel-questions.md](legal/counsel-questions.md) |

Checks: `make pci-scan` (no card data in the schema, data, seeds, contracts, logs or clients; the request guard) and
`make legal-check` (every legal text matches its registered version; sign-offs cover the exact text).
