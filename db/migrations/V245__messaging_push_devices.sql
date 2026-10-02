-- S-102 push notifications and deep links (range V245–V249, docs/IMPLEMENTATION_PLAN.md). Additive only.

-- The device registry: one row per signed-in person, app and app installation. The consumer and courier apps write it
-- with their DPoP-bound tokens (PUT/DELETE /api/v1/me/devices/{installationId}); the worker's push adapters read it
-- and delete a row when APNs or FCM says the token is dead. Nothing personal beyond the token itself: no device name,
-- model, IP address or location.
CREATE TABLE messaging.push_devices (
  id              text        PRIMARY KEY,
  user_id         text        NOT NULL,                     -- logical ref → identity.users
  app             text        NOT NULL CHECK (app IN ('consumer', 'courier')),
  -- random id the app makes once per installation (not a hardware id)
  installation_id text        NOT NULL CHECK (installation_id ~ '^[A-Za-z0-9_-]{16,64}$'),
  platform        text        NOT NULL CHECK (platform IN ('ios', 'android')),
  -- APNs device token (iOS) or FCM registration token (Android); null while the person hasn't allowed notifications
  token           text        CHECK (token IS NULL OR char_length(token) BETWEEN 16 AND 4096),
  locale          text        NOT NULL CHECK (locale IN ('en-CA', 'fr-CA')),
  app_version     text        NOT NULL CHECK (char_length(app_version) BETWEEN 1 AND 32),
  permission      text        NOT NULL CHECK (permission IN ('granted', 'provisional', 'denied', 'undetermined')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  refreshed_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, app, installation_id)
);
-- A token belongs to one installation: registering it for someone else (a shared phone) moves it.
CREATE UNIQUE INDEX ux_push_devices_token ON messaging.push_devices (app, platform, token) WHERE token IS NOT NULL;
CREATE INDEX ix_push_devices_user ON messaging.push_devices (user_id, app);
CREATE INDEX ix_push_devices_refreshed ON messaging.push_devices (refreshed_at);

-- Customers' and couriers' notifications held back (S-27's table, V074, was for team members only): by quiet hours,
-- and — since their consumer group has no Kafka retry topics (Event Hubs' 100-topic limit) — when a provider is down
-- or throttling, email included. Who the notification is for, and no business for an order that spans several shops.
ALTER TABLE messaging.deferred_notifications
  ADD COLUMN audience text NOT NULL DEFAULT 'team' CHECK (audience IN ('team', 'customer', 'courier')),
  ALTER COLUMN merchant_id DROP NOT NULL,
  DROP CONSTRAINT IF EXISTS deferred_notifications_channel_check,
  ADD CONSTRAINT deferred_notifications_channel_check CHECK (channel IN ('sms', 'push', 'email'));
