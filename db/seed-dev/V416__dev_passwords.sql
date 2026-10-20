-- S-154, local profile only (db/seed-dev is never in the images): every seeded persona signs in with email + password.
-- The password is the same for all of them and obviously fake: northline-dev-password (README § Local sign-in).
-- northline-auth refuses it as a new password (auth/common-passwords.txt), so no real account can end up with it.
-- Hash: Argon2id, m=19456 KiB, t=2, p=1 (Spring Security's Argon2PasswordEncoder, as northline-auth hashes passwords).
INSERT INTO auth.passwords (user_id, hash, changed_at)
SELECT id, '{argon2}$argon2id$v=19$m=19456,t=2,p=1$GBbKAsYQ8Cfc3UaFKFGxeQ$cJN7blpwkoxBSPMRm0SNZSp2EZ71uEVNq7f8UXJo8FY', now()
  FROM identity.users
 WHERE email LIKE '%@example.com' AND coalesce(status, 'active') = 'active'
ON CONFLICT (user_id) DO NOTHING;

-- Their addresses count as confirmed (they are the seed's own).
UPDATE identity.users SET email_verified_at = coalesce(email_verified_at, now())
 WHERE email LIKE '%@example.com';
