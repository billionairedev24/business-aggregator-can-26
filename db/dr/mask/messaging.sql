-- messaging: conversations, support cases, notifications and uploads are personal. Push device tokens are deleted so
-- staging never pushes to a real phone.
update messaging.messages
   set body = case when body is null then null else '[masked message]' end,
       sender_name = case when sender_name is null then null else 'Sender ' || lower(right(coalesce(sender_id, id), 6)) end;
update messaging.threads
   set counterpart_name = case when counterpart_name is null then null else 'Contact ' || lower(right(coalesce(counterpart_id, id), 6)) end,
       subject = case when subject is null then null else 'Conversation ' || lower(right(id, 6)) end;
update messaging.tickets
   set subject = case when subject is null then null else 'Support case ' || number end,
       context = pg_temp.nl_scrub(context),
       agent_name = case when agent_name is null then null else 'Agent ' || lower(right(coalesce(agent_id, id), 6)) end,
       ref_label = case when ref_label is null then null else 'Ref ' || lower(right(id, 6)) end,
       resolution_note = null;
update messaging.notifications set payload = pg_temp.nl_scrub(payload) where payload is not null;
update messaging.deferred_notifications set payload = pg_temp.nl_scrub(payload);
update messaging.attachments set file_name = 'attachment-' || lower(right(id, 6));
update messaging.customer_uploads set file_name = 'upload-' || lower(right(id, 6));
update messaging.support_refund_requests set note = null, decision_note = null;
delete from messaging.push_devices;
