import { Bff } from '../src/bff';
import { env, isLocal } from '../src/env';
import { expect, test } from '../src/fixtures';
import { email } from '../src/outbox';

/**
 * Journey 5 — finance: the payout run pays the provider what is available, and the payout shows in Studio › Payouts
 * on its way to the bank. Locally the run is started by the local-only payout-run route (the scheduled run waits for
 * 9:00 on a payout day); a deployed environment pays on schedule, so there the suite checks the latest payout.
 */
test('a payout run pays the provider and the payout shows in Studio finance', async ({ owner }) => {
  const provider = env.data.provider;
  const payouts = `${env.urls.studio}/b/${provider.merchantId}/payouts`;
  const history = owner.getByRole('list', { name: 'Payouts' });
  // "Available now" and the amount are two lines of the balance card
  const availableNow = owner.getByText('Available now', { exact: true }).locator('xpath=following-sibling::*[1]');

  if (!isLocal) {
    await owner.goto(payouts);
    await expect(owner.getByRole('heading', { name: 'Getting your money', level: 1 })).toBeVisible();
    await expect(history.getByRole('listitem').first()).toContainText(/Paid|In transit|Scheduled/);
    test.info().annotations.push({ type: 'target', description: 'payouts run on schedule here; the latest one was checked' });
    return;
  }

  let available = '';
  await test.step('provider: has money available', async () => {
    await owner.goto(payouts);
    await expect(availableNow).toHaveText(/^\$[\d,.]+$/);
    available = (await availableNow.textContent())!.trim();
    expect(available, 'the dev seed, and the booking journey before this one, leave a balance').not.toBe('$0.00');
  });

  const ran = new Date();
  let amountCents = 0;
  await test.step('payments: the payout run', async () => {
    const studio = new Bff(owner.context(), env.urls.studio);
    const { status, body } = await studio.post<{ amountCents: number; state: string; destination: string }>(`/api/v1/dev/merchants/${provider.merchantId}/payouts/run`);
    expect(status).toBe(201);
    amountCents = body.amountCents;
    expect(`$${(amountCents / 100).toLocaleString('en-CA', { minimumFractionDigits: 2 })}`).toBe(available);
  });

  await test.step('provider: the payout is in Studio › Payouts, on its way to the bank', async () => {
    await owner.reload();
    await expect(availableNow).toHaveText('$0.00');
    const latest = history.getByRole('listitem').first();
    await expect(latest.getByRole('definition').first()).toHaveText(available);
    await expect(latest).toContainText('In transit'); // standard payouts arrive in 2–3 business days
  });

  await test.step('provider: told by email', async () => {
    const message = await email(env.personas.owner.identifier, 'payout-sent', ran);
    expect(message.subject).toContain(`${available} is on its way to your bank`);
  });
});
