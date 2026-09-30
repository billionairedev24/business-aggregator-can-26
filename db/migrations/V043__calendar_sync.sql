-- Operations workstream (range V040–V049): S-32 Google and Microsoft calendar two-way sync. Additive only.
-- See docs/DECISIONS.md "S-32" and docs/runbooks/calendar-sync.md.

-- A link now holds an OAuth grant (Google, Outlook): the refresh token sealed with the api's envelope key
-- (ca.northline.shared.crypto; token_ref = the key that wrapped the data key), the provider's account id, the scopes
-- granted, and whether it still works. 'reconnect' = the grant was revoked or expired: nothing syncs until the member
-- connects again. iCal links keep their feed token in token_ref as before.
ALTER TABLE availability.calendar_links
  ADD COLUMN state text NOT NULL DEFAULT 'connected',
  ADD COLUMN external_account_id text,
  ADD COLUMN scopes text[] NOT NULL DEFAULT '{}',
  ADD COLUMN refresh_token_enc bytea,
  ADD COLUMN refresh_token_key bytea,
  ADD COLUMN write_calendar_id text,
  ADD COLUMN last_error text,
  ADD COLUMN state_changed_at timestamptz,
  ADD CONSTRAINT chk_calendar_link_state CHECK (state IN ('connected', 'reconnect')),
  ADD CONSTRAINT chk_calendar_link_sealed CHECK ((refresh_token_enc IS NULL) = (refresh_token_key IS NULL));

-- The member's calendars whose busy times block Northline slots, with the provider's incremental-sync cursor
-- (Google nextSyncToken, Microsoft Graph deltaLink). The primary calendar is chosen on connect.
CREATE TABLE availability.calendar_sources (
  link_id text NOT NULL REFERENCES availability.calendar_links(id) ON DELETE CASCADE,
  calendar_id text NOT NULL,
  name text NOT NULL,
  sync_cursor text,
  window_from timestamptz,
  synced_at timestamptz,
  PRIMARY KEY (link_id, calendar_id)
);
CREATE INDEX ix_calendar_sources_synced ON availability.calendar_sources(synced_at NULLS FIRST);

-- Busy time only: provider event id, start, end. No title, attendees, location or description is ever stored.
CREATE TABLE availability.calendar_busy_blocks (
  link_id text NOT NULL,
  calendar_id text NOT NULL,
  external_event_id text NOT NULL,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  PRIMARY KEY (link_id, calendar_id, external_event_id),
  FOREIGN KEY (link_id, calendar_id) REFERENCES availability.calendar_sources(link_id, calendar_id) ON DELETE CASCADE,
  CONSTRAINT chk_busy_range CHECK (ends_at > starts_at)
);
CREATE INDEX ix_calendar_busy_blocks_time ON availability.calendar_busy_blocks(link_id, starts_at);

-- Change notifications: a Google push channel or a Microsoft Graph subscription per source. The secret we gave the
-- provider (Google channel token / Graph clientState) is kept as a SHA-256 hash only. external_id is null while a
-- Graph subscription is being created (its validation handshake arrives before the create call returns).
CREATE TABLE availability.calendar_channels (
  id text PRIMARY KEY,
  link_id text NOT NULL,
  calendar_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('google', 'outlook')),
  external_id text,
  secret_hash text NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  FOREIGN KEY (link_id, calendar_id) REFERENCES availability.calendar_sources(link_id, calendar_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_calendar_channels_external ON availability.calendar_channels(provider, external_id);
CREATE INDEX ix_calendar_channels_expiry ON availability.calendar_channels(expires_at);

-- Notification dedupe (Google: channel + X-Goog-Message-Number; Graph: hash of subscription, change and etag).
-- Rows older than 7 days are purged by the sync job.
CREATE TABLE availability.calendar_notifications (
  provider text NOT NULL,
  dedupe_key text NOT NULL,
  channel_id text NOT NULL,
  received_at timestamptz NOT NULL,
  PRIMARY KEY (provider, dedupe_key)
);
CREATE INDEX ix_calendar_notifications_received ON availability.calendar_notifications(received_at);

-- Northline bookings written to the member's calendar (write-back), so a reschedule updates and a cancellation
-- deletes the same event, and so our own events never come back as busy blocks. content_hash covers the text and
-- times that were written.
CREATE TABLE availability.calendar_event_mirrors (
  link_id text NOT NULL REFERENCES availability.calendar_links(id) ON DELETE CASCADE,
  booking_id text NOT NULL,
  calendar_id text NOT NULL,
  external_event_id text NOT NULL,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  content_hash text NOT NULL,
  written_at timestamptz NOT NULL,
  PRIMARY KEY (link_id, booking_id)
);
CREATE INDEX ix_calendar_event_mirrors_event ON availability.calendar_event_mirrors(link_id, external_event_id);

-- OAuth authorization requests in flight (authorization code + PKCE). state is kept as a SHA-256 hash; the PKCE
-- verifier never leaves the api. Single use, 10 minutes.
CREATE TABLE availability.calendar_oauth_requests (
  state_hash text PRIMARY KEY,
  merchant_id text NOT NULL,
  member_user_id text NOT NULL,
  provider text NOT NULL CHECK (provider IN ('google', 'outlook')),
  code_verifier text NOT NULL,
  scopes text[] NOT NULL,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE INDEX ix_calendar_oauth_requests_expiry ON availability.calendar_oauth_requests(expires_at);
