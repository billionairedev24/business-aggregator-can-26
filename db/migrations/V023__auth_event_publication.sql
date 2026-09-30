-- S-28 transactional outbox for northline-auth (auth range V020–V029). See docs/DECISIONS.md "S-28 user.registered".
-- northline-auth's own Spring Modulith JDBC event publication registry, in the `auth` schema (same shape as the api's
-- events.event_publication, V017 + V018 indexes). Separate from the api's: both apps republish their outstanding
-- publications at start-up, and neither could deserialise the other's event types.
CREATE TABLE auth.event_publication (
  id                     uuid        PRIMARY KEY,
  listener_id            text        NOT NULL,
  event_type             text        NOT NULL,
  serialized_event       text        NOT NULL,
  publication_date       timestamptz NOT NULL,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    int         NOT NULL DEFAULT 0,
  last_resubmission_date timestamptz
);
CREATE INDEX auth_event_publication_incomplete ON auth.event_publication (publication_date) WHERE completion_date IS NULL;
CREATE INDEX auth_event_publication_serialized_event_hash_idx ON auth.event_publication USING hash(serialized_event);
CREATE INDEX auth_event_publication_by_completion_date_idx ON auth.event_publication (completion_date);

CREATE TABLE auth.event_publication_archive (LIKE auth.event_publication INCLUDING ALL);
