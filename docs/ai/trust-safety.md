# Trust & safety assist (S-133)

The model helps trust & safety staff decide **what to look at first**. Staff make every decision. Nothing is hidden,
rejected, blocked or penalized automatically.

## What it does

- **Screening** (every 15 min, `ScreenTrustContent`):
  - It reads what was written since the last run:
    - listings submitted for vetting (their name, details, category, price and the automated vetting result);
    - reviews of businesses;
    - messages in customer threads.
  - It asks the light model (prompt `trust-screen`) whether a moderator should look at each item. A flag carries one
    or more categories: `off_platform_payment`, `abuse`, `scam`, `prohibited`, `misleading`, `fake_review`,
    `personal_info`, `spam`.
  - It records every verdict in `trust.ai_screenings`. That record holds the verdict and categories, plus the
    explanation for flagged items. It never copies the item's text.
  - Flagged items get an open `ai_screen` flag in `trust.flags`. Its evidence holds `source: ai`, the categories, the
    explanation, the model and the prompt version.
- **Weekly anomaly scan** (Mondays 12:10 UTC, `ScanAnomalies`):
  - Deterministic rules (`AnomalyRules`) compare each business's week with its previous eight:
    - `review_burst`: at least 5 reviews and at least 3× the usual week;
    - `rating_drop`: at least 3 reviews this week and at least 5 before, and the average down by 1 star or more;
    - `off_platform`: 3 or more off-platform payment flags;
    - `ai_flags`: 3 or more screening flags.
  - Businesses are grouped by market: the region market, else the province, else `unplaced`.
  - The standard model (prompt `anomaly-scan`) gets one call per market. It sees letters (A, B, …) and counts only, and
    writes a short explanation for staff for each business.
  - Each business gets an open `anomaly_<week start>` flag.
  - If the model is unavailable, the flag is still raised, with the rules' own explanation (`source: rules`).
- **Staff queue** (console API, role `STAFF` with a second factor):
  - `GET /api/v1/console/trust/flags?state=open|dismissed|actioned|all&source=ai|rules&limit=50` lists every trust
    flag (detector, business report or AI). Each comes with `explanation`, `categories`, `source` and the raw
    `evidence`. Rule flags explain themselves from their rule.
  - `POST /api/v1/console/trust/flags/{id}/decision {decision: dismissed|actioned, note?}` records who decided, when
    and why. It is audited as `trust.flag_decided`. Deciding a flag changes nothing else: what "actioned" means
    (hiding a listing, warning a business) is a separate staff action in the existing tools.
  - There is no console web app yet, so this is API only.

## What is sent to the model

| Item | Sent | Never sent |
|---|---|---|
| Listing | name, "what's included" or description and bullets, category name, price, the category's median price deviation, automated vetting flag codes | merchant name, ids, contact details, images |
| Review | stars and text | author name, ids, the business |
| Message | who wrote it (business or customer), text as stored (contact details already masked), whether the detector flagged it | names, thread subject, ids, the other messages |
| Anomaly scan | per business: signal codes and counts | names, ids, places, any text |

Every request also goes through the port's `PrivacyRedactor` (phone numbers, emails, cards, SINs, bank numbers).
Requests run under the system caller (`system:trust-screening`, `system:anomaly-scan`). That caller has its own daily
token budget, `AI_BUDGET_SYSTEM_TOKENS_PER_DAY`, and no per-minute rate.

## Measuring it

- `ai-eval/trust-screen.json` holds 18 labelled items across listings, reviews and messages: 11 to flag and 7 that are
  fine but tempting (harsh honest reviews, price questions, a clearance price).
  - The eval reports precision and recall of "flag".
  - A flag passes only with the expected category, and with an explanation that doesn't repeat the personal details.
- `ai-eval/anomaly-scan.json` holds six market weeks.
  - The rules must pick exactly the labelled businesses, with the right signals (precision and recall of "flag").
  - The model's explanation must cite the numbers and accuse no one.
- CI runs both against the simulated model. Before trusting the precision figures, run the live eval with a key
  (docs/runbooks/ai.md › Prompts and evals).

## Operating it

See docs/runbooks/ai.md › Trust & safety assist: the variables, turning parts off, the backlog and the cost.
