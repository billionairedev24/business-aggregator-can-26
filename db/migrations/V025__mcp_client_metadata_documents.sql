-- S-127 (docs/runbooks/mcp.md): AI agents may identify themselves with an OAuth Client ID Metadata Document — their
-- client_id is the HTTPS URL of that document, often longer than 100 characters. Widening only; nothing else changes.
ALTER TABLE auth.oauth2_registered_client ALTER COLUMN client_id TYPE varchar(2048);
