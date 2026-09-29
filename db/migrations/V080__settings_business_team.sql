-- Settings & compliance workstream (range V080–V089). See docs/DECISIONS.md "Settings & compliance".
-- Settings › Business and Settings › Team & roles. Additive only.

-- Business tab fields nobody stores yet ("Cancellation policy", "Auto-accept quotes under"). The service area stays in
-- onboarding's merchants.profile jsonb (serviceArea) and languages in merchants.languages.
ALTER TABLE merchants.merchants
  ADD COLUMN cancellation_policy      text CHECK (cancellation_policy IN ('flexible', '12h', '24h')),
  ADD COLUMN auto_accept_quote_cents  bigint CHECK (auto_accept_quote_cents >= 0);

-- When someone joined the team, and who invited them (Team table, audit).
ALTER TABLE merchants.merchant_members
  ADD COLUMN joined_at  timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN invited_by text;

-- Owner invites a teammate by email or mobile; the invitee signs in (or creates their own login) and accepts.
-- Only a SHA-256 of the invitation token is stored.
CREATE TABLE merchants.member_invitations (
  id           text        PRIMARY KEY,
  merchant_id  text        NOT NULL REFERENCES merchants.merchants(id),
  role         text        NOT NULL CHECK (role IN ('owner', 'technician', 'bookkeeper', 'cook')),
  email        citext,
  phone        text,
  token_hash   text        NOT NULL UNIQUE,
  invited_by   text        NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,
  accepted_at  timestamptz,
  accepted_by  text,
  revoked_at   timestamptz,
  CONSTRAINT chk_invitation_contact CHECK (email IS NOT NULL OR phone IS NOT NULL),
  CONSTRAINT chk_invitation_closed CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);
CREATE INDEX ix_member_invitations_merchant ON merchants.member_invitations(merchant_id, created_at DESC);
-- One open invitation per contact per business.
CREATE UNIQUE INDEX ux_member_invitations_open_email ON merchants.member_invitations(merchant_id, email)
  WHERE email IS NOT NULL AND accepted_at IS NULL AND revoked_at IS NULL;
CREATE UNIQUE INDEX ux_member_invitations_open_phone ON merchants.member_invitations(merchant_id, phone)
  WHERE phone IS NOT NULL AND accepted_at IS NULL AND revoked_at IS NULL;
