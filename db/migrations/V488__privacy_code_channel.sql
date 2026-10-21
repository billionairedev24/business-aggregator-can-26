-- S-159 (range V487–V496; first V472–V481, renumbered above S-162 / S-158).
-- a privacy request's verification code goes by SMS to a verified mobile or, for an account
-- without one (an email + password account, S-154), by email to its verified address. Additive. See DECISIONS "S-159".
ALTER TABLE privacy.requests
  ADD COLUMN code_channel text CHECK (code_channel IN ('sms', 'email'));
