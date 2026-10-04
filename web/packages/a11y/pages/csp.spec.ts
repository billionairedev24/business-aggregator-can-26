import { expect, test, type Page } from '@playwright/test';
import { mockApi, open } from './support';
// @ts-expect-error — plain ES module shared with the Node mock api
import { responder } from '../scripts/responder.mjs';

/**
 * S104-09 / SAQ A E7: the consumer site's script policy has no 'unsafe-inline' — every server-rendered page gets a fresh
 * nonce and every inline script carries it. Against the built Node server (`make a11y`, `make csp-check`), with the
 * sweep's mock api; Stripe.js is a stand-in served for https://js.stripe.com (no network, no Stripe account), so the
 * checkout loads a script and a frame from the inventoried origin exactly as the real one does.
 */

const PAGES = ['/', '/search?q=sourdough', '/products/P1', '/services/mobile-mechanic', '/providers/prairie-wrench', '/help', '/sign-in'];

/** A page's policy and nonce, and its inline scripts (no src) with their nonce attribute. */
async function fetchPage(page: Page, path: string) {
  const res = await page.request.get(path);
  expect(res.status(), path).toBe(200);
  const csp = res.headers()['content-security-policy'] ?? '';
  const nonce = /'nonce-([^']+)'/.exec(csp)?.[1];
  const html = await res.text();
  const inline = [...html.matchAll(/<script\b([^>]*)>/g)].filter(m => !/\bsrc=/.test(m[1]!)).map(m => ({ tag: m[0].slice(0, 80), nonce: /\bnonce="([^"]*)"/.exec(m[1]!)?.[1] }));
  return { csp, nonce, inline };
}

/** Collects every CSP violation the page reports (the DOM event and the console line), from before its first script. */
async function watchViolations(page: Page) {
  const seen: string[] = [];
  await page.addInitScript(() => {
    (window as unknown as { __csp: string[] }).__csp = [];
    document.addEventListener('securitypolicyviolation', e => (window as unknown as { __csp: string[] }).__csp.push(`${e.effectiveDirective} ${e.blockedURI} ${e.sourceFile}:${e.lineNumber}`));
  });
  page.on('console', m => { if (/Content Security Policy/i.test(m.text())) seen.push(m.text().slice(0, 200)); });
  return async () => [...seen, ...await page.evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? [])];
}

/** A stand-in for Stripe.js v3: mounts the Payment Element as a frame from js.stripe.com and confirms at once. */
const FAKE_STRIPE = `window.Stripe = function () {
  return {
    elements: function () {
      return {
        create: function () {
          var frame;
          return {
            mount: function (el) { frame = document.createElement('iframe'); frame.title = 'Secure card payment input frame'; frame.src = 'https://js.stripe.com/fake-payment-element.html'; el.appendChild(frame); },
            destroy: function () { if (frame) frame.remove(); },
          };
        },
        submit: function () { return Promise.resolve({}); },
      };
    },
    confirmPayment: function () { return Promise.resolve({ paymentIntent: { id: 'pi_csp_1', status: 'requires_capture', payment_method: 'pm_csp_fake' } }); },
    confirmCardPayment: function () { return Promise.resolve({ paymentIntent: { id: 'pi_csp_2', status: 'requires_capture' } }); },
  };
};`;

test.describe('consumer CSP: nonces, no unsafe-inline (S104-09)', () => {
  test('every page gets a fresh nonce, and every inline script carries it', async ({ page }) => {
    await mockApi(page, 'consumer', { signedIn: false });
    for (const path of PAGES) {
      const a = await fetchPage(page, path);
      const b = await fetchPage(page, path);
      expect(a.csp, path).not.toContain("'unsafe-inline' https://js.stripe.com");
      expect(/script-src ([^;]*)/.exec(a.csp)?.[1], path).not.toContain("'unsafe-inline'");
      expect(a.nonce, `${path}: a nonce in the policy`).toMatch(/^[A-Za-z0-9+/]{22}==$/);
      expect(b.nonce, `${path}: a fresh nonce per response`).not.toBe(a.nonce);
      expect(a.inline.length, `${path}: inline scripts (config, hydration)`).toBeGreaterThan(0);
      for (const s of a.inline) expect(s.nonce, `${path}: ${s.tag}`).toBe(a.nonce);
    }
    // a client cannot choose the nonce
    const forged = await page.request.get('/', { headers: { 'x-nl-csp-nonce': 'forged' } });
    expect(forged.headers()['content-security-policy']).not.toContain("'nonce-forged'");
    expect(await forged.text()).not.toContain('nonce="forged"');
    // files and the other answers carry the policy without a nonce
    const robots = await page.request.get('/robots.txt');
    expect(robots.headers()['content-security-policy']).not.toContain('nonce-');
  });

  test('the main pages load and hydrate with zero violations, and an injected inline script is refused', async ({ page }) => {
    const violations = await watchViolations(page);
    await mockApi(page, 'consumer', { signedIn: false });
    for (const path of PAGES.filter(p => p !== '/sign-in')) {
      await page.setViewportSize({ width: 1280, height: 900 });
      await open(page, 'consumer', path, 'en');
      expect(await violations(), path).toEqual([]);
    }
    // hydrated: a client-side navigation works (the router's scripts ran)
    await open(page, 'consumer', '/', 'en');
    await page.getByRole('contentinfo').getByRole('link', { name: 'Help' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Help' })).toBeVisible();
    expect(await violations()).toEqual([]);
    // the policy is enforced: a script without the nonce does not run
    const ran = await page.evaluate(async () => {
      const s = document.createElement('script');
      s.textContent = 'window.__injected = true';
      document.body.appendChild(s);
      await new Promise(r => setTimeout(r, 50));
      return (window as unknown as { __injected?: boolean }).__injected === true;
    });
    expect(ran).toBe(false);
    expect((await violations()).some(v => v.startsWith('script-src'))).toBe(true);
  });

  test('the signed-in pages (account, orders, booking, cart with the local payment stand-in) load with zero violations', async ({ page }) => {
    const violations = await watchViolations(page);
    await mockApi(page, 'consumer', { signedIn: true });
    await page.setViewportSize({ width: 1280, height: 900 });
    for (const path of ['/account', '/account/orders', '/providers/prairie-wrench/book', '/cart']) {
      await open(page, 'consumer', path, 'en');
      expect(await violations(), path).toEqual([]);
    }
  });

  test('checkout loads Stripe.js and its frame from the inventory only, with zero violations', async ({ page }) => {
    const violations = await watchViolations(page);
    await mockApi(page, 'consumer', { signedIn: true });
    const stripeRequests: string[] = [];
    await page.route(url => url.hostname === 'js.stripe.com', route => {
      stripeRequests.push(route.request().url());
      return route.request().url().endsWith('/v3')
        ? route.fulfill({ contentType: 'text/javascript', body: FAKE_STRIPE })
        : route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>card</title><p>Card number</p>' });
    });
    // the api says Stripe is configured (a test publishable key) and opens one PaymentIntent
    const stripe = { provider: 'stripe', publishableKey: 'pk_test_csp_fake' };
    const answer = responder('consumer') as (m: string, u: string) => { body: Record<string, unknown> };
    await page.route(url => url.pathname === '/api/v1/me/checkout', route => {
      const url = new URL(route.request().url());
      return route.fulfill({ json: { ...answer('GET', url.pathname + url.search).body, payment: stripe } });
    });
    await page.route(url => url.pathname === '/api/v1/me/checkouts', route => route.fulfill({ status: 201, json: {
      checkoutId: 'K1', orderId: 'ORD1', ref: 'NL-50001', totalCents: 4504, expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
      payment: stripe, intents: [{ paymentIntent: 'pi_csp_1', clientSecret: 'pi_csp_1_secret_fake', status: 'requires_payment_method', amountCents: 4504 }],
    } }));
    await page.setViewportSize({ width: 1280, height: 900 });
    await open(page, 'consumer', '/cart', 'en');
    await page.getByRole('button', { name: /^Pay / }).click();
    const frame = page.getByTestId('stripe-payment-element').locator('iframe');
    await expect(frame).toHaveAttribute('src', 'https://js.stripe.com/fake-payment-element.html');
    await expect(page.frameLocator('[data-testid="stripe-payment-element"] iframe').getByText('Card number')).toBeVisible();
    await page.getByRole('button', { name: /^Confirm/ }).click();
    await page.waitForURL(/\/orders\/ORD1/);
    expect(stripeRequests.some(u => u.endsWith('/v3'))).toBe(true);
    expect(await violations()).toEqual([]);
  });
});
