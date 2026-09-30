-- S-26 (foundation range V018–V019): the worker purges consumer dedupe claims older than their retention
-- (northline.events.processed-retention, 60 days) every night; this index keeps that delete from scanning the table.
create index if not exists processed_events_processed_at_idx on events.processed_events (processed_at);
