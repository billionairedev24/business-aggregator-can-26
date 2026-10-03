import type { Page } from '@playwright/test';
import { Bff } from '../src/bff';
import { env } from '../src/env';
import { expect, test, unique } from '../src/fixtures';
import { hydrated } from '../src/hydration';

/**
 * Journey 3 — quote → booking → escrow (services): a customer asks the provider for a quote, the provider writes an
 * itemized quote in the Studio, the customer accepts it (address, step-up with their passkey, the whole amount held in
 * escrow), the provider travels, checks in and completes the job, the customer signs off, and the escrow is released
 * to the provider.
 */
test('a quote request becomes a booking whose escrow is released after sign-off', async ({ owner, consumer }) => {
  const provider = env.data.provider;
  const description = unique('E2E: battery light on, car died twice this week. Run');
  let requestUrl = '';

  await test.step('customer: requests quotes', async () => {
    await consumer.goto(`${env.urls.consumer}/services/${provider.serviceCategory}/quote?provider=${provider.slug}`, { waitUntil: 'networkidle' });
    await (await hydrated(consumer.getByRole('textbox', { name: /Describe the job/ }))).fill(description);
    await consumer.getByLabel('Year').selectOption('2018');
    await consumer.getByLabel('Make').selectOption('Honda');
    await consumer.getByLabel('Model').fill('Civic');
    await consumer.getByRole('button', { name: 'Continue' }).click();
    await consumer.getByRole('button', { name: 'Choose who quotes' }).click();
    await expect(consumer.getByRole('button', { name: new RegExp(`^${provider.name}`) })).toHaveAttribute('aria-pressed', 'true');
    await consumer.getByRole('button', { name: /Send request to 1 provider/ }).click();
    await expect(consumer).toHaveURL(/\/quotes\/requests\/[0-9A-Z]{26}/);
    await expect(consumer.getByRole('row').filter({ hasText: provider.name })).toContainText('Waiting for a quote');
    requestUrl = consumer.url();
  });

  let total = '';
  await test.step('provider: writes an itemized quote in the Studio', async () => {
    await owner.goto(`${env.urls.studio}/b/${provider.merchantId}/appointments`);
    const card = owner.getByRole('article').filter({ hasText: description });
    await card.getByRole('button', { name: 'Write quote' }).click();
    await card.getByLabel('Line 1 item').fill('Replace alternator (remanufactured)');
    await card.getByLabel('Line 1 type').selectOption({ label: 'Labour' });
    await card.getByLabel('Line 1 amount').fill('300');
    // the visit: the provider's first free hour from the day after tomorrow, in the market's time (= the browser's)
    await card.getByLabel('Proposed time').fill(await freeHour(consumer, provider.slug));
    await card.getByLabel('Estimated duration').selectOption({ label: '1 hour' });
    await card.getByLabel(/Scope of work/).fill('Confirm the charging fault and replace the alternator.');
    const send = card.getByRole('button', { name: /Send quote · \$/ });
    total = (await send.textContent())!.match(/\$[\d,.]+/)![0];
    expect(total).toBe('$315.00'); // $300 labour + 5 % GST
    await send.click();
    await expect(card).toContainText(`Quote sent · ${total}`);
  });

  let bookingRef = '';
  let quoteId = '';
  let acceptedAt = new Date();
  await test.step('customer: accepts — the whole quote goes into escrow', async () => {
    await consumer.goto(requestUrl, { waitUntil: 'networkidle' });
    await consumer.getByRole('row').filter({ hasText: provider.name }).getByRole('link', { name: 'View quote' }).click();
    await expect(consumer.getByText('Replace alternator (remanufactured)')).toBeVisible();
    await (await hydrated(consumer.getByLabel('Street address'))).fill(env.data.market.address.street);
    acceptedAt = new Date();
    await consumer.getByRole('button', { name: `Accept · hold ${total}` }).click();
    const stepUp = consumer.getByRole('dialog');
    const accepted = consumer.getByText(/^Accepted\. .* held · booking BK-\d+ created/);
    await expect(stepUp.or(accepted)).toBeVisible();
    if (await stepUp.isVisible()) await stepUp.getByRole('button', { name: /passkey/i }).click(); // "Confirm it's you"
    await expect(accepted).toBeVisible();
    bookingRef = (await accepted.textContent())!.match(/BK-\d+/)![0];
    quoteId = consumer.url().match(/\/quotes\/([0-9A-Z]{26})/)![1]!;
  });

  await test.step('provider: travels, checks in and completes the job', async () => {
    const job = await openJob(owner, provider.merchantId, bookingRef);
    await expect(job).toContainText(/Escrow\$[\d,.]+ held/); // the Studio shows the amount before GST
    await job.getByRole('button', { name: 'Start travel' }).click();
    await job.getByRole('button', { name: 'Check in on site' }).click();
    await job.getByRole('button', { name: 'Complete with photos + report' }).click();
    const dialog = owner.getByRole('dialog', { name: 'Complete the job' });
    await dialog.getByLabel('Report').fill('Alternator replaced, charging at 14.2 V. Belt fine.');
    await dialog.getByRole('button', { name: 'Complete job' }).click();
    await expect(job).toContainText('Waiting for sign-off');
  });

  await test.step('customer: signs off the job', async () => {
    // Sign-off is in the consumer app (S-100); the web has no button for it yet — the same call, through the site's BFF.
    const bff = new Bff(consumer.context(), env.urls.consumer);
    const { bookingId } = await bff.get<{ bookingId: string }>(`/api/v1/me/quotes/${quoteId}`);
    expect((await bff.get<{ ref: string; state: string }>(`/api/v1/me/bookings/${bookingId}`)).state).toBe('completed');
    const { body } = await bff.post<{ state: string }>(`/api/v1/me/bookings/${bookingId}/sign-off`);
    expect(body.state).toBe('signed_off');
  });

  await test.step('provider: the escrow is released', async () => {
    await expect(async () => {
      const job = await openJob(owner, provider.merchantId, bookingRef);
      await expect(job).toContainText('Escrow released.', { timeout: 3_000 });
    }).toPass({ timeout: 90_000 });
  });

  await test.step('payments: the escrow is released into the provider\'s earnings', async () => {
    const studio = new Bff(owner.context(), env.urls.studio);
    type Ledger = { items: { kind: string; occurredAt: string; state: string; grossCents: number }[] };
    await expect.poll(async () => {
      const { items } = await studio.get<Ledger>(`/api/v1/merchants/${provider.merchantId}/earnings/ledger`);
      const entry = items.find(i => i.kind === 'service' && Date.parse(i.occurredAt) >= acceptedAt.getTime() - 5_000);
      return entry && `${entry.state} ${entry.grossCents}`;
    }, { message: 'the booking\'s entry in the provider\'s ledger', timeout: 90_000 }).toBe('released 30000');
  });

  test.info().annotations.push({ type: 'booking', description: bookingRef });
});

/** Appointments › List, week by week until the booking shows up, then its job panel. */
async function openJob(owner: Page, merchantId: string, ref: string) {
  await owner.goto(`${env.urls.studio}/b/${merchantId}/appointments`);
  await owner.getByRole('radiogroup', { name: 'Calendar view' }).getByText('List', { exact: true }).click();
  const open = owner.getByRole('button', { name: new RegExp(`^Open · .* · ${ref}$`) });
  const week = owner.getByRole('heading', { level: 1 });
  for (let weeks = 0; weeks < 4; weeks++) {
    // the week's jobs are drawn (or its empty state) before deciding the booking isn't in it
    await expect(owner.getByRole('button', { name: /^Open · / }).first().or(owner.getByText('No jobs this week.'))).toBeVisible();
    if (await open.isVisible()) break;
    const shown = await week.textContent();
    await owner.getByRole('button', { name: 'Next week' }).click();
    await expect(week).not.toHaveText(shown!);
  }
  await open.click();
  const job = owner.getByRole('region', { name: new RegExp(ref) });
  await expect(job).toBeVisible();
  return job;
}

/**
 * The provider's first free hour (two free half-hour slots in a row) from the day after tomorrow, from its public
 * calendar, as a `datetime-local` value in the market's time zone — free in this database, so reruns never collide.
 */
async function freeHour(page: Page, slug: string): Promise<string> {
  const zone = env.data.market.timeZone;
  const get = async <T>(path: string) => (await (await page.request.get(env.urls.consumer + path)).json()) as T;
  const { services } = await get<{ services: { id: string }[] }>(`/api/v1/public/providers/${slug}`);
  const from = new Intl.DateTimeFormat('en-CA', { timeZone: zone }).format(new Date(Date.now() + 2 * 24 * 3600e3));
  const { days } = await get<{ days: { slots: { startsAt: string; free: boolean }[] }[] }>(
    `/api/v1/public/providers/${slug}/slots?serviceId=${services[0]!.id}&from=${from}&days=7`);
  for (const { slots } of days) {
    const start = slots.find((s, i) => s.free && slots[i + 1]?.free && Date.parse(slots[i + 1]!.startsAt) - Date.parse(s.startsAt) === 30 * 60e3);
    if (!start) continue;
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
      timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(new Date(start.startsAt)).map(p => [p.type, p.value]));
    return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
  }
  throw new Error(`${slug} has no free hour in the week after tomorrow`);
}
