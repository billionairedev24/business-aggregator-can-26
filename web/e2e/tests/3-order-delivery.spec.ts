import { Bff } from '../src/bff';
import { publishProduct } from '../src/catalogue';
import { CourierApp } from '../src/courier';
import { contextOptions } from '../src/fixtures';
import { env, isLocal } from '../src/env';
import { VirtualAuthenticator } from '../src/passkey';
import { consumerRegister, newConsumer } from '../src/signIn';
import { expect, test, unique } from '../src/fixtures';
import { hydrated } from '../src/hydration';

interface Stop { id: string; kind: 'pickup' | 'dropoff'; orderId: string; state: string }

/**
 * Journey 4 — order → pack → deliver (goods): the shop lists a product, a customer buys it for direct courier delivery
 * (fake card, bank approval), the shop marks the order packed in the Studio, dispatch puts a courier on shift and plans
 * the run in the console, and the courier picks up and drops off through the courier API with the customer's PIN. The
 * customer's order page follows it to "Delivered".
 */
test('a shop order is packed, dispatched and delivered by a courier', async ({ browser, owner, staff, consumer }) => {
  const seller = env.data.seller;
  const title = unique('E2E Brake cleaner');

  let listingId = '';
  await test.step('shop: lists a product', async () => {
    listingId = await publishProduct(owner, staff, seller.merchantId, {
      title, brand: 'Northline E2E', description: 'Non-chlorinated brake parts cleaner, 400 g.',
      categoryLevel1: seller.departmentLevel1, categoryLevel2: seller.departmentLevel2, price: '12.50', stock: 5,
    });
  });

  let orderId = '';
  let orderRef = '';
  await test.step('customer: buys it for direct delivery', async () => {
    await consumer.goto(`${env.urls.consumer}/products/${listingId}`, { waitUntil: 'networkidle' });
    await expect(consumer.getByRole('heading', { level: 1 })).toHaveText(title);
    await (await hydrated(consumer.getByRole('button', { name: 'Add 1 to cart · $12.50' }))).click();
    await expect(consumer.getByRole('link', { name: /^Cart, \d+ items?$/ })).toBeVisible();
    await consumer.goto(`${env.urls.consumer}/cart`, { waitUntil: 'networkidle' });
    await expect(consumer.getByRole('region', { name: seller.name })).toContainText(title);
    await (await hydrated(consumer.getByRole('radio', { name: /Direct courier/ }))).click();
    const street = consumer.getByLabel('Street address');
    if (await street.isVisible()) { // a new customer has no saved address yet
      await street.fill(env.data.market.address.street);
      await consumer.getByLabel('Postal code').fill(env.data.market.address.postalCode);
    }
    await consumer.getByRole('button', { name: /^Pay \$/ }).click();
    await consumer.getByRole('group', { name: /Verified by Visa/ }).getByRole('button', { name: 'Approve' }).click();
    await expect(consumer).toHaveURL(/\/orders\/[0-9A-Z]{26}/);
    await expect(consumer.getByRole('heading', { level: 1 })).toContainText('Order placed.');
    orderId = consumer.url().match(/\/orders\/([0-9A-Z]{26})/)![1]!;
    orderRef = (await consumer.getByText(/^NL-\d+ · /).textContent())!.match(/NL-\d+/)![0];
  });

  await test.step('shop: marks it packed in the Studio', async () => {
    await owner.goto(`${env.urls.studio}/b/${seller.merchantId}/orders`);
    await owner.getByRole('button', { name: `Mark packed · ${orderRef}` }).click();
    await expect(owner.getByRole('button', { name: `Mark packed · ${orderRef}` })).toBeHidden();
    await expect(consumer.getByRole('listitem').filter({ hasText: 'Shops packing' })).toContainText('1 of 1 packed', { timeout: 30_000 });
  });

  // The courier: their own browser for the sign-in, then the courier app's DPoP-bound calls.
  const courierContext = await browser.newContext(contextOptions());
  const courierPage = await courierContext.newPage();
  let app!: CourierApp;
  let courierUserId = env.personas.courier?.userId ?? '';
  await test.step('courier: signs in to the courier app', async () => {
    if (isLocal) {
      // A new courier every run (sign-up with a phone code and a passkey), so no run left over from an earlier attempt
      // keeps them busy.
      await VirtualAuthenticator.attach(courierPage).then(a => consumerRegister(courierPage, newConsumer('Kai'), a));
      courierUserId = ((await (await courierPage.request.get(`${env.urls.consumer}/bff/session`)).json()) as { user: { id: string } }).user.id;
      app = await CourierApp.authorize(courierPage);
    } else {
      const courier = env.personas.courier;
      if (!courier || !courierUserId) throw new Error('E2E_COURIER_IDENTIFIER and E2E_COURIER_USER_ID are not set (docs/runbooks/e2e.md)');
      app = await CourierApp.signIn(courierPage, courier);
    }
  });

  await test.step('dispatch (console): the courier is on shift and the order\'s run is theirs', async () => {
    const consoleApi = new Bff(staff.context(), env.urls.console);
    const market = env.data.market.name;
    type Courier = { id: string; userId: string; status: string; active: boolean };
    let mine = (await consoleApi.get<{ items: Courier[] }>(`/api/v1/console/fulfilment/couriers?market=${encodeURIComponent(market)}`)).items.find(c => c.userId === courierUserId);
    if (!mine) mine = (await consoleApi.post<Courier>('/api/v1/console/fulfilment/couriers', { userId: courierUserId, market, vehicle: 'ebike' })).body;
    const now = Date.now();
    await consoleApi.post(`/api/v1/console/fulfilment/couriers/${mine.id}/shifts`, {
      startsAt: new Date(now - 5 * 60e3).toISOString(), endsAt: new Date(now + 3 * 3600e3).toISOString(),
    });
    const { body: shifts } = await app.call<{ items: { id: string; state: string }[] }>('GET', '/api/v1/courier/shifts');
    const shift = shifts.items.find(s => s.state === 'on') ?? shifts.items.find(s => s.state !== 'ended')!;
    if (shift.state !== 'on') expect((await app.call<{ state: string }>('POST', `/api/v1/courier/shifts/${shift.id}/start`)).body.state).toBe('on');
    // Paused while dispatch plans, so the automatic assignment can't hand them a run left over from an earlier attempt
    // on the same database; then dispatch gives them this order's run by hand.
    await consoleApi.post(`/api/v1/console/fulfilment/couriers/${mine.id}/pause`, { reason: 'E2E: waiting for the test order' });
    const { body: current } = await consoleApi.get<{ items: Courier[] }>(`/api/v1/console/fulfilment/couriers?market=${encodeURIComponent(market)}`).then(r => ({ body: r.items.find(c => c.id === mine!.id)! }));
    expect(current.status, 'a run left over from an earlier attempt reached the courier first — rerun on a fresh database').not.toBe('on_run');

    let runId = '';
    await expect.poll(async () => {
      await consoleApi.post('/api/v1/console/fulfilment/plan', { market });
      runId = (await consoleApi.get<{ run: { id: string } | null }>(`/api/v1/console/fulfilment/orders/${orderId}`)).run?.id ?? '';
      return runId;
    }, { message: 'a run planned for the order' }).not.toBe('');
    await consoleApi.post(`/api/v1/console/fulfilment/couriers/${mine.id}/resume`);
    await consoleApi.post(`/api/v1/console/fulfilment/runs/${runId}/assign`, { courierId: mine.id });
    const { body: run } = await app.call<{ id: string; stops: Stop[] }>('GET', '/api/v1/courier/run');
    expect(run.id).toBe(runId);
  });

  let pin = '';
  await test.step('customer: sees the courier and their drop-off PIN', async () => {
    await consumer.reload();
    const pinLine = consumer.getByText(/Drop-off PIN \d+/);
    await expect(pinLine).toBeVisible();
    pin = (await pinLine.textContent())!.match(/Drop-off PIN (\d+)/)![1]!;
  });

  await test.step('courier: picks up at the shop and drops off with the PIN', async () => {
    const run = async () => (await app.call<{ stops: Stop[] }>('GET', '/api/v1/courier/run')).body.stops.filter(s => s.orderId === orderId);
    const stops = await run();
    const pickup = stops.find(s => s.kind === 'pickup')!;
    const dropoff = stops.find(s => s.kind === 'dropoff')!;
    await app.call('POST', `/api/v1/courier/stops/${pickup.id}/arrive`);
    await app.call('POST', `/api/v1/courier/stops/${pickup.id}/pickup`, { scanOk: true });
    await app.call('POST', `/api/v1/courier/stops/${dropoff.id}/arrive`);
    await app.call('POST', `/api/v1/courier/stops/${dropoff.id}/dropoff`, { proof: 'pin', pin });
  });
  await courierContext.close();

  await test.step('customer: the order is delivered', async () => {
    await expect(async () => {
      await consumer.reload();
      await expect(consumer.getByText('Delivered with your PIN.')).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 60_000 });
  });
});
