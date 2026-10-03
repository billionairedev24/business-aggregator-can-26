import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { I18nProvider } from '@northline/ui';
import { describe, expect, it } from 'vitest';
import { mockFetch } from '../../test/render';
import { PilotControl } from './PilotControl';

const page = (signedIn: boolean) => render(
  <I18nProvider initial="fr"><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><PilotControl signedIn={signedIn} /></QueryClientProvider></I18nProvider>,
);

describe('the consumer site\'s "Send feedback" control (S-121)', () => {
  it('is for signed-in pilot customers, in their language', async () => {
    window.history.pushState({}, '', '/checkout?pi=pi_secret_123');
    const calls = mockFetch(c => {
      if (c.url.endsWith('/api/v1/me/pilot')) return { body: { participant: true, persona: 'customer', screenshotMaxBytes: 5242880, screenshotTypes: ['image/png'] } };
      if (c.url.endsWith('/api/v1/me/pilot/feedback')) return { status: 201, body: { id: 'F1', reference: 'UAT-1003' } };
      return undefined;
    });
    page(true);
    await userEvent.click(await screen.findByRole('button', { name: 'Envoyer un commentaire' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByLabelText('Une idée'));
    await userEvent.type(within(dialog).getByLabelText('Que s’est-il passé?'), 'Montrer le dépôt sur la confirmation.');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Envoyer' }));
    expect(await within(dialog).findByText(/UAT-1003/)).toBeTruthy();
    expect(calls.find(c => c.url.endsWith('/feedback'))!.body).toMatchObject({ app: 'consumer', category: 'idea', route: '/checkout', locale: 'fr-CA' });
  });

  it('asks nothing for visitors who are not signed in', async () => {
    const calls = mockFetch(() => undefined);
    page(false);
    await waitFor(() => expect(screen.queryByRole('button')).toBeNull());
    expect(calls).toHaveLength(0);
  });
});
