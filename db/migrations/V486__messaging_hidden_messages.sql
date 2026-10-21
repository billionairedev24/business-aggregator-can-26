-- S-158: trust & safety can hide a message the AI screening flagged (console › Trust & safety › AI screening). The
-- thread then shows "This message was hidden by Northline." in its place; the words move to hidden_body for an
-- appeal and are erased with the rest of the person's data (MessagingPersonalData). Additive only.
ALTER TABLE messaging.messages
  ADD COLUMN hidden_at   timestamptz,
  ADD COLUMN hidden_by   text,
  ADD COLUMN hidden_body text;
