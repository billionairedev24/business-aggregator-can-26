-- S-27 (messaging range V070–V079): SMS and push notifications held back by a team member's quiet hours
-- (messaging.notification_prefs.quiet_from/quiet_to, America/Edmonton). Written and drained only by the worker's
-- notifications consumer; the payload is the domain event itself (ids and amounts only, no personal data) so the
-- message is rendered — and the member's preferences re-checked — when it is finally sent.
create table if not exists messaging.deferred_notifications (
  id            text        primary key,
  channel       text        not null check (channel in ('sms', 'push')),
  user_id       text        not null,          -- logical ref: identity.users.id
  merchant_id   text        not null,          -- logical ref: merchants.merchants.id
  event_id      text        not null,
  event_type    text        not null,
  event_version int         not null,
  payload       jsonb       not null,
  due_at        timestamptz not null,
  attempts      int         not null default 0,
  created_at    timestamptz not null default now(),
  unique (channel, user_id, event_id)
);

create index if not exists deferred_notifications_due_idx on messaging.deferred_notifications (due_at);
