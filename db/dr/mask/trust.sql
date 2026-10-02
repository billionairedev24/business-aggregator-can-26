-- trust: review text, replies, authors and reports are personal; reviews are immutable (trigger), so the trigger is
-- off for this transaction only. Flag evidence, points notes and AI prompts are scrubbed.
alter table trust.reviews disable trigger reviews_immutable;
update trust.reviews
   set text = case when text is null then null else '[masked review]' end,
       reply = case when reply is null then null else '[masked reply]' end,
       author_name = case when author_name is null then null else 'Customer ' || lower(right(author_id, 6)) end,
       report_note = null;
alter table trust.reviews enable trigger reviews_immutable;
update trust.flags set evidence = pg_temp.nl_scrub(evidence), decision_note = null;
update trust.points_ledger set note = null where note is not null;
update trust.ai_screenings set prompt = '[masked]';
update trust.anomaly_scans set prompt = null where prompt is not null;
