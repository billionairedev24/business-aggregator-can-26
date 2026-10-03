-- S-120: the console's merchant success role (pilot onboarding: invites, the pipeline board, kitchen visits).
ALTER TABLE identity.platform_roles DROP CONSTRAINT platform_roles_role_check;
ALTER TABLE identity.platform_roles ADD CONSTRAINT platform_roles_role_check
  CHECK (role IN ('staff', 'admin', 'trust_safety', 'dispatch', 'finance', 'support', 'support_lead', 'analyst',
                  'privacy', 'merchant_success'));
