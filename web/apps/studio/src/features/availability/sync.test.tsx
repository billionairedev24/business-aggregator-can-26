import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockFetch, renderWithProviders } from '../../test/ops';
import { goTo, type Calendar } from './api';
import { SyncTab } from './SyncTab';

let role = 'technician';
vi.mock('../shell/api', () => ({ useMerchantId: () => 'm1', useRole: () => role }));

const A = '/api/v1/merchants/m1/availability';
const cal = (c: Partial<Calendar> & Pick<Calendar, 'provider'>): Calendar => ({ connected: false, available: true, sources: [], ...c });
const sync = (calendars: Calendar[]) => ({ calendars, team: [] });

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); role = 'technician'; });

describe('Calendar sync (S-32)', () => {
  it('Connect sends the browser to the provider’s consent page', async () => {
    const assign = vi.spyOn(goTo, 'assign').mockImplementation(() => undefined);
    const calls = mockFetch({
      [`GET ${A}/sync`]: () => sync([cal({ provider: 'google' }), cal({ provider: 'outlook', available: false }), cal({ provider: 'ical' })]),
      [`POST ${A}/calendars/google`]: () => cal({ provider: 'google', authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?state=s' }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<SyncTab />);
    const google = (await screen.findByText('Google Calendar')).closest('li')!;
    const outlook = screen.getByText('Outlook / Microsoft 365').closest('li')!;
    expect(within(outlook).getByText('Not available yet')).toBeTruthy();
    expect((within(outlook).getByRole('button', { name: 'Connect' }) as HTMLButtonElement).disabled).toBe(true);
    await user.click(within(google).getByRole('button', { name: 'Connect' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('https://accounts.google.com/o/oauth2/v2/auth?state=s'));
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/calendars/google'))).toBe(true);
  });

  it('a revoked grant shows Reconnect, which starts OAuth again', async () => {
    const assign = vi.spyOn(goTo, 'assign').mockImplementation(() => undefined);
    mockFetch({
      [`GET ${A}/sync`]: () => sync([cal({ provider: 'google', connected: true, state: 'reconnect', accountLabel: 'jas@prairiewrench.ca' }), cal({ provider: 'outlook' }), cal({ provider: 'ical' })]),
      [`POST ${A}/calendars/google`]: () => cal({ provider: 'google', connected: true, state: 'reconnect', authorizationUrl: 'https://accounts.google.com/again' }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<SyncTab />);
    expect(await screen.findByText('Access expired or was removed · reconnect to keep syncing')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Choose calendars' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Reconnect' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('https://accounts.google.com/again'));
  });

  it('shows what blocks slots, and a connected calendar disconnects', async () => {
    const calls = mockFetch({
      [`GET ${A}/sync`]: () => sync([cal({ provider: 'google', connected: true, state: 'connected', accountLabel: 'jas@prairiewrench.ca', lastSyncAt: new Date().toISOString(), sources: ['Work', 'Family'] }), cal({ provider: 'outlook' }), cal({ provider: 'ical' })]),
      [`DELETE ${A}/calendars/google`]: () => cal({ provider: 'google' }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<SyncTab />);
    expect(await screen.findByText('Google Calendar · jas@prairiewrench.ca')).toBeTruthy();
    expect(screen.getByText('Two-way · last sync just now · Blocks slots from: Work, Family')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Connected' }));
    await waitFor(() => expect(calls.some(c => c.method === 'DELETE')).toBe(true));
    await waitFor(() => expect(within(screen.getByText('Google Calendar').closest('li')!).getByText('Not connected')).toBeTruthy());
  });

  it('after the callback: a notice, then Choose calendars validates and saves', async () => {
    const calls = mockFetch({
      [`GET ${A}/sync`]: () => sync([cal({ provider: 'google', connected: true, state: 'connected', sources: ['Work'] }), cal({ provider: 'outlook' }), cal({ provider: 'ical' })]),
      [`GET ${A}/calendars/google/sources`]: () => ({ items: [{ id: 'primary', name: 'Work', primary: true, selected: true }, { id: 'fam', name: 'Family', primary: false, selected: false }] }),
      [`PUT ${A}/calendars/google/sources`]: (_u, init) => ({ items: JSON.parse(String(init.body)).calendarIds.map((id: string) => ({ id, name: id, primary: false, selected: true })) }),
    });
    const seen = vi.fn();
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<SyncTab returned={{ calendar: 'google', result: 'connected', choose: true }} onReturnSeen={seen} />);
    expect(await screen.findByText('Google Calendar is connected. Busy times will block your slots within a few minutes.')).toBeTruthy();
    expect(seen).toHaveBeenCalled();
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('Work (main calendar)');
    await user.click(within(dialog).getByText('Work (main calendar)'));
    await user.click(within(dialog).getByRole('button', { name: 'Save calendars' }));
    expect(within(dialog).getByRole('alert').textContent).toBe('Choose at least one calendar.');
    await user.click(within(dialog).getByText('Family'));
    await user.click(within(dialog).getByRole('button', { name: 'Save calendars' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ calendarIds: ['fam'] }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('asks the provider for the calendar list first when needed (incremental consent)', async () => {
    const assign = vi.spyOn(goTo, 'assign').mockImplementation(() => undefined);
    mockFetch({
      [`GET ${A}/sync`]: () => sync([cal({ provider: 'google', connected: true, state: 'connected' }), cal({ provider: 'outlook' }), cal({ provider: 'ical' })]),
      [`GET ${A}/calendars/google/sources`]: () => ({ items: [], authorizationUrl: 'https://accounts.google.com/list' }),
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<SyncTab />);
    await user.click(await screen.findByRole('button', { name: 'Choose calendars' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('Google Calendar first needs to let Northline see your list of calendars.')).toBeTruthy();
    await user.click(within(dialog).getByRole('button', { name: 'Continue to Google Calendar' }));
    expect(assign).toHaveBeenCalledWith('https://accounts.google.com/list');
  });

  it('speaks French (fr-CA), including the outcome notices', async () => {
    mockFetch({
      [`GET ${A}/sync`]: () => sync([cal({ provider: 'google', connected: true, state: 'reconnect' }), cal({ provider: 'outlook' }), cal({ provider: 'ical' })]),
    });
    renderWithProviders(<SyncTab returned={{ calendar: 'outlook', result: 'denied' }} />, 'fr');
    expect(await screen.findByText("L'accès n'a pas été autorisé dans Outlook / Microsoft 365; rien n'a été connecté.")).toBeTruthy();
    expect(screen.getByText('Accès expiré ou retiré · reconnectez pour continuer la synchro')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Reconnecter' })).toBeTruthy();
  });

  it('bookkeepers see the calendars but can’t connect them', async () => {
    role = 'bookkeeper';
    mockFetch({ [`GET ${A}/sync`]: () => sync([cal({ provider: 'google' }), cal({ provider: 'outlook' }), cal({ provider: 'ical' })]) });
    renderWithProviders(<SyncTab />);
    const google = (await screen.findByText('Google Calendar')).closest('li')!;
    expect((within(google).getByRole('button', { name: 'Connect' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
