# Writing help (S-131)

Four AI drafting aids in the Studio. Each one returns a **draft** (`aiAssisted: true`) that the person edits. Nothing
is saved, sent or published by the AI.

| Where | What | Endpoint | Prompt | Eval set |
|---|---|---|---|---|
| Listing editor | title, description, bullets in English and French from the editor's facts | `POST /api/v1/merchants/{id}/listing-copy` | `listing-copy` | `listing-copy.json` |
| Quote composer | 2–6 lines (kind, description, qty — never prices) and questions to ask | `POST …/quote-requests/{requestId}/line-suggestions` | `quote-lines` | `quote-lines.json` |
| Messages | three replies to the customer's last message | `POST …/threads/{threadId}/reply-suggestions` | `message-reply` | `message-reply.json` |
| Reviews | a summary and themes of the latest verified reviews, in English and French | `POST …/reviews/summary-draft` | `review-summary` | `review-summary.json` |

- Listings saved from a draft go through vetting like any other listing.
- Replies are never sent: a click only fills the message box.
- The prompts forbid inventing prices, promises or claims, and forbid sharing contact details or paying outside
  Northline. The port's redaction masks contact details anyway.
- The minimum data each feature sends to the model is listed in DECISIONS.md (S-131).
