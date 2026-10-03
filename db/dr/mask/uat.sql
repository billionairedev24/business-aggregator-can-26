-- uat (S-121): pilot participants' feedback text, staff's triage notes and sign-off comments are free text people
-- typed; labels may name a business. Screenshots stay in the bucket under uat/ (the bucket copy is the operator's call,
-- backups-dr.md), their keys are dropped here so staging never serves them.
update uat.participants set label = 'Participant ' || lower(right(id, 6));
update uat.feedback
   set body = '[masked feedback ' || number || ']',
       platform = 'masked',
       screenshot_key = null, screenshot_type = null, screenshot_bytes = null;
update uat.feedback_history set note = null where note is not null;
update uat.signoffs set comments = case when comments is null then null else '[masked comments]' end;
delete from uat.screenshots;
