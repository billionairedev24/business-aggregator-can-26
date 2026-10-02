-- S-104 security review (range V305–V309). Additive only.
-- Every verification code texted for a privacy request (S-105), so the texts per person are capped across requests:
-- opening, withdrawing and reopening a request no longer sends texts without limit (SMS cost, and each new code gave
-- five more guesses). Ids and times only; rows older than two days are deleted as new ones are written.
CREATE TABLE privacy.verification_texts (
  id         text        PRIMARY KEY,
  subject_id text        NOT NULL,                 -- logical ref → identity.users
  request_id text        NOT NULL REFERENCES privacy.requests (id) ON DELETE CASCADE,
  sent_at    timestamptz NOT NULL
);

CREATE INDEX verification_texts_subject_idx ON privacy.verification_texts (subject_id, sent_at);
