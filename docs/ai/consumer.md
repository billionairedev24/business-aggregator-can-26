# Consumer AI (S-132)

- **Natural-language search:** `POST /api/v1/search/interpret` turns "cheap halal pho open now" into the search API's
  own filters (`kind=food`, `dietary=halal`, `openNow`, `sort=price_asc`, …).
  - The server keeps only values the search API accepts.
  - Distance needs a shared location.
  - It never runs the search: the app shows the filters as chips and searches with them.
  - Guests can use it; the budget is per signed-in user, guest or hashed address.
- **Help triage:** `POST /api/v1/me/help/triage` turns a "something's wrong" report into a suggested case category, a
  route (`refund_request`, `dispute`, `support`) and a neutral summary for staff.
  - The route comes from a fixed rule, not the model.
  - Safety reports are always urgent.
  - Nothing is opened and no refund is decided: the customer submits the case, and merchants or staff decide.
- Eval sets: `search-filters.json` (filters, invented values dropped) and `help-triage.json` (precision and recall per
  category, safety never missed).
- The UIs belong to S-48 (search results) and S-60 ("something's wrong"). The contracts are in CONSUMER_WEB_PLAN.md.
