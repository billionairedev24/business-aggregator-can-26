-- S-83 platform console support desk (console queues range V210–V219, docs/IMPLEMENTATION_PLAN.md). Additive only.

-- Support leads keep the desk's macros (design: "macros EN/FR … editable by support leads").
ALTER TABLE identity.platform_roles DROP CONSTRAINT platform_roles_role_check;
ALTER TABLE identity.platform_roles ADD CONSTRAINT platform_roles_role_check
  CHECK (role IN ('staff', 'admin', 'trust_safety', 'dispatch', 'finance', 'support', 'support_lead', 'analyst'));

-- Agent work on a case: the first reply (median first reply, SLA), escalation to trust & safety, who changed it.
ALTER TABLE messaging.tickets
  ADD COLUMN first_replied_at timestamptz,
  ADD COLUMN escalated_at     timestamptz,
  ADD COLUMN escalated_by     text;
CREATE INDEX IF NOT EXISTS ix_tickets_open ON messaging.tickets (created_at) WHERE state <> 'resolved';

-- Support macros: canned replies in English and French (topic 'support'), with a title for the picker.
ALTER TABLE messaging.macros
  ADD COLUMN title_i18n jsonb,
  ADD COLUMN updated_by text,
  ADD COLUMN updated_at timestamptz;

INSERT INTO messaging.macros (id, key, topic, position, title_i18n, body_i18n, updated_at) VALUES
  ('01J9ZD3VSM0000000000000001', 'support.payout_hold', 'support', 1,
   '{"en":"Payout hold — policy explanation","fr":"Retenue de versement — explication de la politique"}',
   '{"en":"After a dispute is decided against a business, its payouts are held for 7 days so any further refund can be covered. The hold lifts on its own; nothing is lost.","fr":"Après un litige tranché contre une entreprise, ses versements sont retenus 7 jours pour couvrir un éventuel autre remboursement. La retenue se lève d’elle-même; rien n’est perdu."}', now()),
  ('01J9ZD3VSM0000000000000002', 'support.refund_approved', 'support', 2,
   '{"en":"Refund approved — what happens next","fr":"Remboursement approuvé — la suite"}',
   '{"en":"Your refund is approved. It goes back to your original payment method within 5–10 business days; you’ll get an email when it is sent.","fr":"Votre remboursement est approuvé. Il retourne sur votre moyen de paiement d’origine d’ici 5 à 10 jours ouvrables; vous recevrez un courriel à l’envoi."}', now()),
  ('01J9ZD3VSM0000000000000003', 'support.document_received', 'support', 3,
   '{"en":"Document received — re-verification timeline","fr":"Document reçu — délai de revérification"}',
   '{"en":"Thanks, we received your document. Compliance re-verifies it within 2 business days and we’ll restore anything that was paused once it is confirmed.","fr":"Merci, nous avons reçu votre document. La conformité le revérifie d’ici 2 jours ouvrables et nous rétablirons ce qui était suspendu une fois confirmé."}', now()),
  ('01J9ZD3VSM0000000000000004', 'support.late_delivery', 'support', 4,
   '{"en":"Late delivery — fee refunded automatically","fr":"Livraison en retard — frais remboursés automatiquement"}',
   '{"en":"Sorry your delivery was late. The delivery fee is refunded automatically to your payment method; no action is needed.","fr":"Désolés pour le retard de votre livraison. Les frais de livraison sont remboursés automatiquement sur votre moyen de paiement; rien à faire."}', now()),
  ('01J9ZD3VSM0000000000000005', 'support.reschedule_policy', 'support', 5,
   '{"en":"Reschedule policy — deposits","fr":"Politique de report — dépôts"}',
   '{"en":"A new date needs the provider’s agreement. Your deposit stays in escrow and moves to the new date; the deposit rules don’t change.","fr":"Une nouvelle date exige l’accord du prestataire. Votre dépôt reste en fiducie et suit la nouvelle date; les règles du dépôt ne changent pas."}', now()),
  ('01J9ZD3VSM0000000000000006', 'support.standard_reply', 'support', 6,
   '{"en":"Français · réponse standard","fr":"Français · réponse standard"}',
   '{"en":"Thanks for writing to Northline. We’re looking into it and will get back to you shortly.","fr":"Merci d’avoir écrit à Northline. Nous examinons votre demande et vous répondrons sous peu."}', now());

-- Refund requests from the support desk to finance ("refund request to finance"): an agent asks, someone with the
-- refund action (finance, admin) — never the requester — approves or declines. Nothing is paid by the request itself.
CREATE TABLE messaging.support_refund_requests (
  id            text        PRIMARY KEY,
  ticket_id     text        NOT NULL REFERENCES messaging.tickets(id),
  amount_cents  bigint      NOT NULL CHECK (amount_cents > 0),
  note          text        CHECK (char_length(note) <= 1000),
  requested_by  text        NOT NULL,
  requested_at  timestamptz NOT NULL,
  state         text        NOT NULL CHECK (state IN ('pending', 'approved', 'declined')),
  decided_by    text,
  decided_at    timestamptz,
  decision_note text        CHECK (char_length(decision_note) <= 1000)
);
CREATE INDEX ix_support_refund_requests_ticket ON messaging.support_refund_requests(ticket_id, requested_at DESC);
CREATE INDEX ix_support_refund_requests_pending ON messaging.support_refund_requests(requested_at) WHERE state = 'pending';
