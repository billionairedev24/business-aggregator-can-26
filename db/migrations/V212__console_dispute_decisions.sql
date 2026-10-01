-- S-80 platform console disputes & refunds (console queues range V210–V219, docs/IMPLEMENTATION_PLAN.md). Additive only.

-- Every agent decision on a dispute or an escalated refund case. A decision that returns more than $500 to the customer
-- (design 03: Finance "refunds > $500 · Passkey + 2nd approver") waits for a finance co-sign before any money moves;
-- the escrow stays on hold meanwhile.
CREATE TABLE payments.agent_decisions (
  id            text        PRIMARY KEY,
  case_kind     text        NOT NULL CHECK (case_kind IN ('dispute', 'refund')),
  case_id       text        NOT NULL,
  merchant_id   text        NOT NULL,
  -- full_refund | partial | release | goodwill_credit (the platform pays a Northline credit; the seller keeps the money)
  outcome       text        NOT NULL CHECK (outcome IN ('full_refund', 'partial', 'release', 'goodwill_credit')),
  -- what goes back to the customer: card refund (full_refund, partial) or Northline credit (goodwill_credit)
  refund_cents  bigint      NOT NULL DEFAULT 0 CHECK (refund_cents >= 0),
  note          text        CHECK (char_length(note) <= 1000),
  decided_by    text        NOT NULL,
  role          text        NOT NULL,
  decided_at    timestamptz NOT NULL,
  -- applied: money moved · awaiting_cosign: above the threshold, waiting for finance · declined: the co-signer refused
  state         text        NOT NULL CHECK (state IN ('applied', 'awaiting_cosign', 'declined')),
  cosigned_by   text,
  cosign_role   text,
  cosigned_at   timestamptz,
  cosign_note   text        CHECK (char_length(cosign_note) <= 1000)
);
CREATE INDEX ix_agent_decisions_case ON payments.agent_decisions(case_kind, case_id, decided_at DESC);
CREATE INDEX ix_agent_decisions_waiting ON payments.agent_decisions(decided_at) WHERE state = 'awaiting_cosign';

-- The agent's note on a decided dispute ("visible to both parties"); also carried by dispute.decided.
ALTER TABLE payments.disputes ADD COLUMN decision_note text CHECK (char_length(decision_note) <= 1000);

-- The console queue: cases waiting for an agent, oldest first.
CREATE INDEX IF NOT EXISTS ix_disputes_agent ON payments.disputes(opened_at) WHERE state IN ('agent', 'appealed');
CREATE INDEX IF NOT EXISTS ix_refunds_agent_review ON payments.refunds(created_at) WHERE state = 'agent_review';
