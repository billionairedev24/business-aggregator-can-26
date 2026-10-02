-- developer: prod API keys stop working (hash replaced, revoked); webhook endpoints are switched off and pointed at a
-- non-routable host, so staging never calls a merchant's real system; audit snapshots are scrubbed. The audit log is
-- append-only (trigger): the trigger is off for this transaction only.
update developer.api_keys set key_hash = sha256(convert_to('masked:' || id, 'UTF8')), revoked_at = coalesce(revoked_at, now());
update developer.webhook_endpoints
   set url = 'https://webhooks.example.invalid/' || id, active = false,
       secret_enc = null, secret_prev_enc = null, secret_prev_until = null;
update developer.webhook_attempts set response_snippet = null where response_snippet is not null;
update developer.webhook_deliveries set response_snippet = null where response_snippet is not null;
alter table developer.audit_log disable trigger audit_log_append_only;
update developer.audit_log set before = pg_temp.nl_scrub(before), after = pg_temp.nl_scrub(after)
 where before is not null or after is not null;
alter table developer.audit_log enable trigger audit_log_append_only;
