-- region: waitlist sign-ups without an account are e-mail addresses.
update region.waitlist set email = pg_temp.nl_email('w', id) where email is not null;
