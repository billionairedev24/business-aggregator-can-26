-- 2026-09-30 (S-129 AI platform, range V125–V129): one row per AI request (a draft, a classification, one assistant
-- question with all its model calls). Cost accounting per business and feature, and the trace from an answer to the
-- prompt version and model that produced it. Never any content: no prompt, answer or tool result is stored.
-- See docs/DECISIONS.md (S-129) and docs/runbooks/ai.md.

CREATE SCHEMA IF NOT EXISTS ai;

CREATE TABLE ai.usage (
  id                text PRIMARY KEY,                          -- ULID
  feature           text NOT NULL,                             -- AiFeature code: assistant, listing_copy, …
  person_id         text NOT NULL,                             -- identity.users id, or a hashed visitor key
  merchant_id       text,                                      -- the business the request was made for (logical ref)
  provider          text NOT NULL,                             -- openrouter | fake
  model             text NOT NULL,                             -- the model that answered, e.g. google/gemini-3.7-flash
  prompt            text NOT NULL,                             -- versioned prompt id, e.g. listing-copy@v1
  model_calls       int  NOT NULL DEFAULT 0 CHECK (model_calls >= 0),
  prompt_tokens     bigint NOT NULL DEFAULT 0 CHECK (prompt_tokens >= 0),
  completion_tokens bigint NOT NULL DEFAULT 0 CHECK (completion_tokens >= 0),
  cost_micro_usd    bigint CHECK (cost_micro_usd >= 0),        -- the provider's figure in millionths of a USD
  latency_ms        bigint NOT NULL DEFAULT 0,
  tool_runs         int  NOT NULL DEFAULT 0,
  outcome           text NOT NULL CHECK (outcome IN ('ok', 'error', 'rate_limited', 'unavailable')),
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX usage_merchant_idx ON ai.usage (merchant_id, created_at DESC) WHERE merchant_id IS NOT NULL;
CREATE INDEX usage_feature_idx ON ai.usage (feature, created_at DESC);
