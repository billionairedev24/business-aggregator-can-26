# AI (OpenRouter) — S-129 and the AI features

Every AI feature in Northline (the Studio assistant, writing help, natural-language search, help triage, trust &
safety assist; S-130–S-133) goes through one port, `ca.northline.ai.api.LlmClient`, selected by
`northline.ai.provider` (`AI_PROVIDER`). Features never call the port directly: they use `AiCompletions`, which adds
per-person and per-business budgets, the feature's model, the bounded tool loop and a usage record.

| Provider | What it does | Where |
|---|---|---|
| `fake` (default) | deterministic answers, no network or key: "(fake model) You asked: …", and for JSON requests the example block of the prompt | local, test, dev (refused under staging/prod) |
| `openrouter` | OpenRouter's OpenAI-compatible `POST /chat/completions` with attribution headers, usage accounting (tokens + cost) and the no-training / zero-retention data policy | staging, prod (and dev once it has a key) |

Without `OPENROUTER_API_KEY` the `openrouter` provider still starts: every AI endpoint answers **503
`ai_unavailable`** and `GET /api/v1/ai/status` says `available: false`, so the apps hide their AI actions. That is the
state of every cloud environment until a key is created (DECISIONS.md, "AI provider and data residency").

## Privacy (read before enabling)

The product owner accepted OpenRouter as a US processor on 2026-09-30 (DECISIONS.md "AI provider and data
residency"): Northline's data at rest stays in Canada, and only per-request prompts leave. The conditions every
feature follows:

- **Minimum data.** Tools and prompts carry ids, names of listings/services, dates, amounts and states. Never contact
  details, never another business's data (tools are scoped to the authorized `merchantId`).
- **Redaction on the port.** `PrivacyRedactor` masks card numbers and SINs (Luhn-checked), bank account numbers
  (cheque format, "account 1234567", IBAN), emails, phone numbers and API keys in every message before it leaves.
- **No training, no retention.** Every request carries `provider: {data_collection: "deny", zdr: true}`: OpenRouter
  only routes to endpoints that neither store nor train on prompts and have a Zero Data Retention policy. Keep
  `OPENROUTER_DATA_COLLECTION=deny` and `OPENROUTER_ZDR=true`. Also leave OpenRouter's account-level *Input & Output
  Logging* off (Settings › Privacy) and enable the account-wide ZDR guardrail.
- **Nothing stored.** `ai.usage` keeps who, which feature/model/prompt version, tokens, cost and outcome; never a
  prompt, answer or tool result. Logs never contain prompts.
- **Disclosure.** OpenRouter (and the model providers it routes to) must be listed as processors in the Privacy Policy
  and in the PIPEDA / Law 25 privacy impact assessment (SEC stories) before a key is created in prod.

OpenRouter offers in-region routing for the EU and the US only (Business/Enterprise plans), not Canada.

## Models

| Tier | Default (`OPENROUTER_MODEL`, `OPENROUTER_LIGHT_MODEL`) | Features |
|---|---|---|
| standard | `google/gemini-3.7-flash` ($0.75 / $3.75 per M tokens in/out, Sep 2026) | assistant, listing copy, quote lines, anomaly scan |
| light | `google/gemini-3.5-flash-lite` ($0.30 / $2.50) | insights, reply suggestions, review summaries, search filters, help triage, trust screening |

Chosen for tool calling, English/French quality and price. Override per feature with `OPENROUTER_MODEL_<FEATURE>`
(`ASSISTANT`, `INSIGHT`, `LISTING_COPY`, `QUOTE_LINES`, `MESSAGE_REPLY`, `REVIEW_SUMMARY`, `SEARCH_FILTERS`,
`HELP_TRIAGE`, `TRUST_SCREEN`, `ANOMALY_SCAN`), e.g. `OPENROUTER_MODEL_ASSISTANT=anthropic/claude-haiku-4.5`. Before
changing a model: check on openrouter.ai that it supports tools and has ZDR endpoints (with `zdr: true` a model
without one answers 404 "no endpoints", which the app shows as 503), then run the live eval below.

## Variables

| Variable | Default | Notes |
|---|---|---|
| `AI_PROVIDER` | `fake` | **required** in staging/prod: `openrouter` (`fake` refused at start-up) |
| `OPENROUTER_API_KEY` | empty | secret (`openrouter-api-key` in the secrets manager); empty = 503 |
| `OPENROUTER_MODEL` / `OPENROUTER_LIGHT_MODEL` | see Models | |
| `OPENROUTER_MODEL_<FEATURE>` | empty | per-feature override |
| `OPENROUTER_BASE_URL` | `https://openrouter.ai/api/v1` | a stand-in for tests |
| `OPENROUTER_REFERER`, `OPENROUTER_TITLE` | `STUDIO_ORIGIN`, `Northline` | attribution headers `HTTP-Referer`, `X-Title` |
| `OPENROUTER_DATA_COLLECTION`, `OPENROUTER_ZDR` | `deny`, `true` | keep |
| `OPENROUTER_CONNECT_TIMEOUT`, `OPENROUTER_READ_TIMEOUT` | `5s`, `60s` | read = until the first byte (streams keep going) |
| `AI_MAX_TOOL_ROUNDS` | `4` | assistant tool rounds per question; the last round must answer |
| `AI_BUDGET_PERSON_TOKENS_PER_DAY` | `200000` | per person per day (UTC days: a cost window, no market time zone) |
| `AI_BUDGET_MERCHANT_TOKENS_PER_DAY` | `1000000` | per business (its whole team) per day |
| `AI_REQUESTS_PER_MINUTE` | `20` | AI requests (questions, drafts) one person may start per minute |
| `AI_FAKE_LATENCY_MS`, `AI_FAKE_USD_PER_MTOK` | `0` | fake only: make local dashboards look like a real model |

Over a budget: **429 `ai_rate_limited`** with `Retry-After` and `limit` = `person_rate` | `person_tokens` |
`merchant_tokens` | `provider` (OpenRouter throttling). Budgets live in Valkey (`nl:ai:{p:<user>}:…`,
`nl:ai:{m:<merchant>}:…`); if Valkey is down, AI answers 503 rather than running unmetered. Under `local`/`test` they
are in memory.

## Set up per environment

1. openrouter.ai: an organization account for Northline, credits, and **Settings › Privacy**: Input & Output Logging
   **off**, ZDR guardrail **on**, "allow providers that may train on inputs" **off**.
2. **Settings › Keys › Create key** per environment (`northline-dev`, `northline-staging`, `northline-prod`) with a
   monthly credit limit (start at USD 50 dev / 100 staging / what finance agrees for prod).
3. Put it in the environment's secrets manager as `openrouter-api-key` (Terraform created the empty secret: AWS
   `northline/<env>/openrouter-api-key`, Google Cloud `northline-<env>-openrouter-api-key`, Azure `openrouter-api-key`).
4. Helm values: `apps.api.secretEnv.OPENROUTER_API_KEY: true` and `externalSecrets.optionalKeys += OPENROUTER_API_KEY`
   (an empty remote secret must not be mapped: it fails the whole ExternalSecret). `AI_PROVIDER: openrouter` is already
   set in `values-staging.yaml` / `values-prod.yaml`; dev keeps `fake` until you set it.
5. Restart the api; its log says `AI: OpenRouter at … (models …, data_collection=deny, zdr=true)`.
6. Check: `GET /api/v1/ai/status` → `{"available": true, "provider": "openrouter"}`, then ask the Studio assistant
   "How many orders do I have to pack?" and see `northline_ai_cost_usd_total` move.

Rotation: create a new key, update the secret, restart the api, delete the old key.

## Studio assistant (S-130)

- **Endpoints, under `/api/v1/merchants/{merchantId}/assistant`:**
  - `POST /chat` (JSON) and `POST /chat/stream` (SSE: `tool`, `delta`, `done` | `error`);
  - `GET /insights/{dashboard|earnings|listings}`;
  - `POST /actions` (a confirmed write).
- Every endpoint needs membership and `acr=mfa`. Tools run with the caller's role.
- **Through the BFF:** SSE needs no setting (Gateway MVC streams `text/event-stream`). If an ingress or proxy in front
  buffers responses, turn its buffering off for `/api/`. The api sends `X-Accel-Buffering: no` and
  `Cache-Control: no-cache, no-transform`.
- **Turning it off:** unset `OPENROUTER_API_KEY` (or set `AI_PROVIDER=openrouter` without a key). The Studio hides the
  button and the insight cards.
- **Audit:** confirmed writes appear in Settings › Security › Audit log as `assistant.action_confirmed`.

## Dashboards and alerts

The port is wrapped by `ObservedLlmClient` (redaction, traces, metrics). Tags: `provider`, `model`, `feature`,
`outcome` (`ok` | `rate_limited` | `timeout` | `unavailable` | `error`), `streamed` — never ids or text.

| Metric (Prometheus) | What |
|---|---|
| `northline_ai_completion_seconds_{count,sum,bucket}` | model calls and latency (also a trace span `northline.ai.completion`) |
| `northline_ai_tokens_total{kind=prompt|completion}` | tokens |
| `northline_ai_cost_usd_total` | spend in USD, OpenRouter's own figure |
| `northline_ai_call_cost_usd{quantile}` | cost per call (p50, p95) |

Grafana: `deploy/observability/dashboards/northline-ai.json` (calls, errors, latency p95, tokens, **cost per call**
and spend per feature/model). Suggested alerts: error share > 10 % for 10 min; spend today > the key's daily share.
Per-business spend: `select merchant_id, feature, sum(cost_micro_usd)/1e6 usd from ai.usage where created_at >
now() - interval '30 days' group by 1, 2 order by 3 desc;`

## Prompts and evals

- Prompts are versioned files: `server/api/src/main/resources/ai/prompts/<name>.v<N>.md`. Change a prompt by adding the
  next version (the highest one is served); `ai.usage.prompt` records `name@vN` for every request.
- Every feature has a labelled set in `server/api/src/test/resources/ai-eval/<name>.json` and an eval suite
  (`ca.northline.ai.eval`). CI runs each against the **simulated model** (it replays the set's `mock` through the real
  OpenRouter adapter and a local stand-in) — this proves the plumbing, grading and refusals, not the model.
- **Live eval** (opt-in, costs a few cents): runs every suite against the real OpenRouter and fails below each suite's
  gate (0.8 by default).

  ```sh
  cd server
  OPENROUTER_API_KEY=sk-or-v1-… [OPENROUTER_MODEL=… OPENROUTER_LIGHT_MODEL=…] [AI_EVAL_MIN_PASS=0.8] \
    ./gradlew :api:test --tests '*AiEvalLiveTest'
  ```

  Reports: `server/api/build/ai-eval/<suite>-live.{md,json}` (pass rate, precision/recall, tokens, cost). Run it before
  changing a model or a prompt.

## Troubleshooting

| Symptom | Cause |
|---|---|
| 503 `ai_unavailable` "not configured" | no `OPENROUTER_API_KEY` |
| 503 "answered with an error (404)" | the model has no ZDR / no-training endpoint: pick another or check the model id |
| 503 "(402)" | the key's credit limit or the account's credits are spent |
| 429 `limit: provider` | OpenRouter throttling; retried after `Retry-After` |
| 429 `limit: merchant_tokens` | the business spent its daily budget (`AI_BUDGET_MERCHANT_TOKENS_PER_DAY`) |
| app refuses to start: `AI_PROVIDER=fake … refused` | staging/prod with the fake: set `AI_PROVIDER=openrouter` |

What has **never** run against the real OpenRouter: everything — the adapter is tested against a local stand-in built
from OpenRouter's documented API; the live eval is the first real call.
