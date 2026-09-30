import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MERCHANT, storefront } from '../../test/fixtures';
import { mockFetch, renderWithProviders } from '../../test/render';
import { CustomDomainField } from './CustomDomainField';
import { storefrontQuery, type Storefront } from './api';

beforeEach(() => vi.unstubAllGlobals());

const TOKEN = 'nl-abcdefghijklmnopqrstuvwxyz234567';

function withDomain(domain: string, over: Partial<Storefront> = {}, setup: Partial<NonNullable<Storefront['customDomainSetup']>> = {}): Storefront {
  const apex = setup.apex ?? false;
  return storefront({
    customDomain: domain,
    customDomainStatus: 'pending',
    customDomainSetup: {
      target: 'pages.northline.ca',
      apex,
      records: [
        apex ? { type: 'ALIAS', name: domain, value: 'pages.northline.ca' } : { type: 'CNAME', name: domain, value: 'pages.northline.ca' },
        { type: 'TXT', name: `_northline-verify.${domain}`, value: TOKEN },
      ],
      problem: 'txt_missing',
      checkedAt: '2026-10-01T15:00:00Z',
      nextCheckAt: '2026-10-01T15:05:00Z',
      verifiedAt: null,
      liveAt: null,
      graceEndsAt: null,
      ...setup,
    },
    ...over,
  });
}

function renderField(s: Storefront, { locale = 'en' as 'en' | 'fr', canEdit = true } = {}) {
  const calls = mockFetch(call => {
    if (call.url.endsWith('/storefront/domain/verify')) return { body: { ...s, customDomainStatus: 'verified', customDomainSetup: { ...s.customDomainSetup!, problem: null } } };
    if (call.url.endsWith('/storefront/domain/dns')) return { body: { ...s, customDomainStatus: 'live', customDomainSetup: { ...s.customDomainSetup!, problem: null } } };
    return undefined;
  });
  const r = renderWithProviders(<CustomDomainField storefront={s} canEdit={canEdit} />, { locale });
  r.client.setQueryData(storefrontQuery(MERCHANT).queryKey, s);
  return { ...r, calls };
}

describe('CustomDomainField', () => {
  it('shows the design hint with the CNAME target before any domain is entered', () => {
    renderField(storefront());
    expect(screen.getByText('Point a CNAME at pages.northline.ca; we issue the certificate. Your northline.ca address keeps working.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Check now' })).toBeNull();
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('lists the CNAME and the ownership TXT to add, and what is still missing', () => {
    renderField(withDomain('book.aspen.ca'));
    expect(screen.getByRole('status').textContent).toBe("Records not found yet · we check again every few minutes. The TXT record isn't there yet.");
    const rows = screen.getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByText('CNAME')).toBeTruthy();
    expect(within(rows[0]!).getByText('book.aspen.ca')).toBeTruthy();
    expect(within(rows[0]!).getByText('pages.northline.ca')).toBeTruthy();
    expect(within(rows[1]!).getByText('_northline-verify.book.aspen.ca')).toBeTruthy();
    expect(within(rows[1]!).getByText(TOKEN)).toBeTruthy();
    expect(screen.getByText(/Keep both: the TXT record proves the domain is yours/)).toBeTruthy();
  });

  it('copies a record value', async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderField(withDomain('book.aspen.ca'));
    await userEvent.click(screen.getByRole('button', { name: 'Copy TXT value' }));
    expect(writeText).toHaveBeenCalledWith(TOKEN);
    expect(await screen.findByText('Copied')).toBeTruthy();
  });

  it('explains root domains: ALIAS / ANAME / flattening, or www', () => {
    renderField(withDomain('aspen.ca', {}, { apex: true }));
    expect(screen.getByText("aspen.ca is a root domain, which can't have a CNAME. If your DNS host supports ALIAS, ANAME or CNAME flattening, point it at pages.northline.ca. Otherwise connect www.aspen.ca and forward aspen.ca to it.")).toBeTruthy();
    expect(screen.getByText('ALIAS / ANAME')).toBeTruthy();
  });

  it('Check now asks the api and shows the new state', async () => {
    const { calls } = renderField(withDomain('book.aspen.ca'));
    await userEvent.click(screen.getByRole('button', { name: 'Check now' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith(`/merchants/${MERCHANT}/storefront/domain/verify`))).toBe(true));
  });

  it('dev builds can simulate the DNS records', async () => {
    const { calls } = renderField(withDomain('book.aspen.ca'));
    await userEvent.click(screen.getByRole('button', { name: 'Simulate DNS records →' }));
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith(`/dev/merchants/${MERCHANT}/storefront/domain/dns`))).toBe(true));
  });

  it('live: certificate issued, no records or Check now', () => {
    renderField(withDomain('book.aspen.ca', { customDomainStatus: 'live' }, { problem: null, liveAt: '2026-10-01T15:10:00Z' }));
    expect(screen.getByRole('status').textContent).toBe('Verified · certificate issued.');
    expect(screen.queryByRole('list')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Check now' })).toBeNull();
  });

  it('warns during the grace period with the date it stops serving', () => {
    renderField(withDomain('book.aspen.ca', { customDomainStatus: 'live' }, { problem: 'not_pointing', graceEndsAt: '2026-10-04T15:00:00Z' }));
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Your domain stopped pointing at Northline. Fix the records by Oct 4');
    expect(alert.textContent).toContain('it must point only at pages.northline.ca');
  });

  it('staff see the state read-only', () => {
    renderField(withDomain('book.aspen.ca'), { canEdit: false });
    expect((screen.getByRole('textbox', { name: 'Custom domain (optional)' }) as HTMLInputElement).disabled).toBe(true);
    expect(screen.queryByRole('button', { name: 'Check now' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Simulate DNS records →' })).toBeNull();
  });

  it('speaks French (fr-CA)', () => {
    renderField(withDomain('book.aspen.ca', { customDomainStatus: 'expired' }), { locale: 'fr' });
    expect(screen.getByText('Faites pointer un CNAME vers pages.northline.ca; nous émettons le certificat. Votre adresse northline.ca continue de fonctionner.')).toBeTruthy();
    expect(screen.getByRole('status').textContent).toContain('Nous avons cessé de vérifier après 7 jours.');
    expect(screen.getByRole('button', { name: 'Vérifier maintenant' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Copier la valeur TXT' })).toBeTruthy();
  });
});
