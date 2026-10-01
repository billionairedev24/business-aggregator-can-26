-- S-90 platform console (range V190–V199, docs/IMPLEMENTATION_PLAN.md): the staff roles of design 03 next to `staff`
-- (which opens the console) and `admin`. Carried in the access token's `roles` claim; the api enforces each console
-- endpoint by role (shared.security.StaffRole, @RequiresConsole). Widening the CHECK only: existing rows stay valid.
ALTER TABLE identity.platform_roles DROP CONSTRAINT platform_roles_role_check;
ALTER TABLE identity.platform_roles ADD CONSTRAINT platform_roles_role_check
  CHECK (role IN ('staff', 'admin', 'trust_safety', 'dispatch', 'finance', 'support', 'analyst'));

-- Who granted a role (another admin, or a provisioning script = NULL); grants are audit-logged by the granter.
ALTER TABLE identity.platform_roles ADD COLUMN granted_by text;

-- The console's audit views read a person's own trail ("My audit trail") and the platform's (no business).
CREATE INDEX IF NOT EXISTS ix_audit_log_platform ON developer.audit_log(at DESC) WHERE merchant_id IS NULL;
