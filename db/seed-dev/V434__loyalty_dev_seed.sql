-- Dev-only seed (profile `local`, S-152): a running provider-funded reward, a Plus member price and an invite code, so
-- a local run shows "rewards near you", Plus prices on product cards and Invite a neighbour. Nothing here is billed.

-- Prairie Wrench funds 2× points on brake jobs for the next 30 days, with a $50 budget (keeps an existing reward's terms).
INSERT INTO trust.merchant_rewards (id, merchant_id, scope, multiplier, active, label, ends_on, updated_at, updated_by,
                                    budget_cents, spent_cents, started_at)
VALUES ('01J9ZD3VRW0000000000000001', '01J9ZD3V00000000000000PWM1', '"all"'::jsonb, 2, true, 'brake jobs',
        current_date + 30, now(), '01J9ZD3V00000000000000RAV1', 5000, 0, now() - interval '1 day')
ON CONFLICT (merchant_id) DO UPDATE SET
  budget_cents = coalesce(trust.merchant_rewards.budget_cents, excluded.budget_cents),
  started_at = coalesce(trust.merchant_rewards.started_at, excluded.started_at);

-- Prairie Wrench Parts gives Plus members 10 % off its products.
UPDATE catalogue.offers SET member_discount_pct = 10
 WHERE merchant_id = '01J9ZD3V00000000000000PWP1' AND member_discount_pct IS NULL;

-- Amara Osei's invite code.
INSERT INTO loyalty.referral_codes (user_id, code, created_at)
VALUES ('01J9ZD3V0000000000000C0001', 'AMARAK26', now())
ON CONFLICT DO NOTHING;
