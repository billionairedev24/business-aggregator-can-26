-- 2026-09-29 (messaging, help & reviews workstream): Studio Messages (design/02 › messages). Additive only.
-- Display columns the baseline lacks, unread state per business, attachments and quick replies. See docs/DECISIONS.md.

-- Threads: one conversation per booking / order / dispute (customer or Northline support) or helpdesk case.
ALTER TABLE messaging.threads
  ADD COLUMN merchant_id      text,
  -- customer = with a customer about a job/order · support = Northline writes to the business (disputes, notices)
  -- case = the conversation of a helpdesk case (messaging.tickets), shown under Help, never in the inbox
  ADD COLUMN kind             text NOT NULL DEFAULT 'customer' CHECK (kind IN ('customer', 'support', 'case')),
  ADD COLUMN counterpart_id   text,
  ADD COLUMN counterpart_name text,          -- display snapshot: "Amara Osei", "M. Tran", "Northline support"
  ADD COLUMN subject          text,          -- linked context: "brake inspection Tue 9:00"
  ADD COLUMN ref_code         text,          -- human code of ref_id: BK-7712, NL-48213, DS-1188
  ADD COLUMN assignee_id      text,          -- team member on the job (technicians see only their own threads)
  ADD COLUMN merchant_read_at timestamptz,   -- the team last opened the thread (shared inbox: one read mark per business)
  ADD COLUMN created_at       timestamptz NOT NULL DEFAULT now();

CREATE INDEX threads_merchant_idx ON messaging.threads (merchant_id, kind, last_message_at DESC);
CREATE INDEX threads_ref_idx ON messaging.threads (ref_type, ref_id);

ALTER TABLE messaging.messages
  ADD COLUMN sender_role text CHECK (sender_role IN ('merchant', 'customer', 'agent', 'system')),
  ADD COLUMN sender_name text;               -- display snapshot ("Ravi", "Dev K.")
ALTER TABLE messaging.messages ALTER COLUMN flagged SET DEFAULT false;
ALTER TABLE messaging.messages ADD CONSTRAINT messages_thread_fk FOREIGN KEY (thread_id) REFERENCES messaging.threads (id);
CREATE INDEX messages_thread_at_idx ON messaging.messages (thread_id, at);

-- Files attached to messages and helpdesk cases. Bytes live in object storage (AttachmentStorage port);
-- messaging.messages.attachments holds these ids.
CREATE TABLE messaging.attachments (
  id           text PRIMARY KEY,
  merchant_id  text NOT NULL,
  storage_key  text NOT NULL,
  file_name    text NOT NULL,
  content_type text NOT NULL,
  byte_size    bigint NOT NULL CHECK (byte_size > 0),
  uploaded_by  text NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX attachments_merchant_idx ON messaging.attachments (merchant_id);

-- Quick replies are macros with topic 'quick_reply', per portal, in display order.
ALTER TABLE messaging.macros
  ADD COLUMN portals  text[],
  ADD COLUMN position smallint NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX macros_key_uq ON messaging.macros (key);

INSERT INTO messaging.macros (id, key, topic, portals, position, body_i18n) VALUES
  ('01J9ZD3VQR0000000000000001', 'provider.on_my_way',     'quick_reply', '{provider,both}', 1, '{"en":"On my way","fr":"Je suis en route"}'),
  ('01J9ZD3VQR0000000000000002', 'provider.running_late',  'quick_reply', '{provider,both}', 2, '{"en":"Running 15 min late","fr":"J''ai 15 min de retard"}'),
  ('01J9ZD3VQR0000000000000003', 'provider.job_complete',  'quick_reply', '{provider,both}', 3, '{"en":"Job complete — please sign off","fr":"Travail terminé — merci de confirmer"}'),
  ('01J9ZD3VQR0000000000000004', 'provider.extra_parts',   'quick_reply', '{provider,both}', 4, '{"en":"Extra parts approval","fr":"Approbation de pièces supplémentaires"}'),
  ('01J9ZD3VQR0000000000000005', 'seller.packed',          'quick_reply', '{seller,both}',   5, '{"en":"Packed — it goes out on the next run","fr":"Emballé — part avec la prochaine tournée"}'),
  ('01J9ZD3VQR0000000000000006', 'seller.out_of_stock',    'quick_reply', '{seller,both}',   6, '{"en":"Out of stock — can I suggest an alternative?","fr":"En rupture — puis-je proposer une autre option ?"}'),
  ('01J9ZD3VQR0000000000000007', 'seller.pickup_ready',    'quick_reply', '{seller}',        7, '{"en":"Ready for pickup","fr":"Prêt pour le ramassage"}'),
  ('01J9ZD3VQR0000000000000008', 'kitchen.preparing',      'quick_reply', '{kitchen}',       1, '{"en":"Your order is being prepared","fr":"Votre commande est en préparation"}'),
  ('01J9ZD3VQR0000000000000009', 'kitchen.running_late',   'quick_reply', '{kitchen}',       2, '{"en":"Running 10 min late","fr":"10 min de retard"}'),
  ('01J9ZD3VQR000000000000000A', 'kitchen.ready',          'quick_reply', '{kitchen}',       3, '{"en":"Ready for pickup","fr":"Prêt pour le ramassage"}'),
  ('01J9ZD3VQR000000000000000B', 'kitchen.item_86',        'quick_reply', '{kitchen}',       4, '{"en":"An item is sold out — can we swap it?","fr":"Un plat est épuisé — pouvons-nous le remplacer ?"}');
