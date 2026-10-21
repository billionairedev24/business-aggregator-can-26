import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

/** S-157: the console's offers — preview in both languages with the count, a test to oneself, the send, the history. */
const CAMPAIGN = {
  id: 'OC1', market: 'BC', channel: 'email', titleEn: 'Free delivery this weekend', titleFr: 'Livraison gratuite ce week-end', path: '/shop',
  recipients: 120, pending: 40, givenUp: 1, sentBy: 'S1', sentAt: '2026-10-18T17:00:00Z', finishesAt: '2026-10-18T17:04:00Z',
};
const OVERVIEW = { markets: ['AB', 'BC'], perMinute: 30, campaigns: [CAMPAIGN] };
const rendered = (lang: 'en' | 'fr') => ({
  subject: lang === 'en' ? 'Free delivery this weekend — Northline' : 'Livraison gratuite ce week-end — Northline',
  html: '<p>…</p>',
  text: lang === 'en' ? 'Order before Sunday night.\nNorthline Marketplace Inc. sent you this marketing message… Unsubscribe' : 'Commandez avant dimanche soir.',
  sms: lang === 'en' ? 'Northline: Free delivery this weekend. Order before Sunday night. — Northline Marketplace Inc. · Opt out: https://x or reply STOP' : 'Northline : Livraison… ou répondez ARRET',
  smsSegments: 2,
});
const PREVIEW = { en: rendered('en'), fr: rendered('fr'), audience: { market: 'AB', email: 120, sms: 0 } };

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => extra?.(c)
    ?? (c.method === 'GET' && c.url.endsWith('/api/v1/console/offers') ? { body: OVERVIEW }
      : c.url.endsWith('/api/v1/console/offers/preview') ? { body: PREVIEW }
        : c.url.endsWith('/api/v1/console/offers/test') ? { status: 200 } // the api answers 204 (a mocked Response can't carry 204)
          : c.method === 'POST' && c.url.endsWith('/api/v1/console/offers') ? { status: 201, body: { ...CAMPAIGN, id: 'OC2', market: 'AB', pending: 120, givenUp: 0 } }
            : undefined));
}

async function fill(u: ReturnType<typeof userEvent.setup>) {
  await u.type(await screen.findByLabelText('Title (English)'), 'Free delivery this weekend');
  await u.type(screen.getByLabelText('Message (English)'), 'Order before Sunday night.');
  await u.type(screen.getByLabelText('Title (French)'), 'Livraison gratuite ce week-end');
  await u.type(screen.getByLabelText('Message (French)'), 'Commandez avant dimanche soir.');
}

describe('offers (S-157)', () => {
  it('previews both languages with the CASL footer and the count, tests, then sends after confirming', async () => {
    const calls = api(['finance']);
    renderConsole('/offers');
    expect(await screen.findByRole('cell', { name: 'Free delivery this weekend' })).toBeTruthy(); // the history
    expect(screen.getByRole('heading', { level: 1, name: 'Offers' })).toBeTruthy();
    await expectNoAxeViolations(document.body);
    const u = userEvent.setup();
    await fill(u);
    await u.click(screen.getByRole('button', { name: 'Preview' }));
    expect(await screen.findByText('120 customers in AB agreed to offers by email.')).toBeTruthy();
    expect(screen.getByText('Subject: Free delivery this weekend — Northline')).toBeTruthy();
    expect(within(screen.getByRole('article', { name: 'French' })).getByText('Subject: Livraison gratuite ce week-end — Northline')).toBeTruthy();
    expect(calls.find(c => c.url.endsWith('/offers/preview'))!.body).toEqual({
      market: 'AB', channel: 'email', titleEn: 'Free delivery this weekend', bodyEn: 'Order before Sunday night.',
      titleFr: 'Livraison gratuite ce week-end', bodyFr: 'Commandez avant dimanche soir.', path: '/shop',
    });
    await u.click(screen.getByRole('button', { name: 'Send me a test' }));
    expect(await screen.findByText('Test sent to your own email.')).toBeTruthy();
    await u.click(screen.getByRole('button', { name: 'Send to 120 customers' }));
    const dialog = await screen.findByRole('dialog', { name: 'Send this offer?' });
    expect(dialog.textContent).toContain('120 customers in AB by email, 30 a minute');
    await u.click(within(dialog).getByRole('button', { name: 'Send' }));
    expect(await screen.findByText(/^Queued for 120 customers\. The last one goes out around /)).toBeTruthy();
    await waitFor(() => expect(calls.filter(c => c.method === 'POST' && c.url.endsWith('/api/v1/console/offers'))).toHaveLength(1));
  });

  it('a text offer shows its segments; the server’s 422 lands on its field', async () => {
    api(['admin'], c => (c.url.endsWith('/offers/preview') && (c.body as { path: string }).path === 'shop'
      ? { status: 422, body: { errors: [{ field: 'path', rule: 'format', message: 'Link to a page of the site, starting with /.' }] } } : undefined));
    renderConsole('/offers');
    const u = userEvent.setup();
    await fill(u);
    await u.click(screen.getByRole('radio', { name: 'Text' }));
    await u.clear(screen.getByLabelText(/Page it opens/));
    await u.type(screen.getByLabelText(/Page it opens/), 'shop');
    await u.click(screen.getByRole('button', { name: 'Preview' }));
    expect(await screen.findByText('Link to a page of the site, starting with /.')).toBeTruthy();
    await u.clear(screen.getByLabelText(/Page it opens/));
    await u.type(screen.getByLabelText(/Page it opens/), '/shop');
    await u.click(screen.getByRole('button', { name: 'Preview' }));
    expect(await screen.findByText('Nobody in AB has agreed to offers by text yet.')).toBeTruthy();
    expect(screen.getAllByText('2 text messages long')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: /^Send to/ })).toBeNull(); // nobody to send to
  });

  it('a role that doesn’t open the screen gets the denied banner, not the composer', async () => {
    api(['analyst']);
    renderConsole('/offers');
    await waitFor(() => expect(screen.queryByLabelText('Title (English)')).toBeNull());
    expect(screen.queryByRole('button', { name: 'Send me a test' })).toBeNull();
  });

  it('is in French', async () => {
    api(['finance']);
    renderConsole('/offers', { locale: 'fr' });
    expect(await screen.findByRole('heading', { name: 'Offres envoyées' })).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1, name: 'Offres' })).toBeTruthy();
    expect(screen.getByLabelText('Titre (français)')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'M’envoyer un essai' })).toBeTruthy();
  });
});
