import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { mockFetch, renderWithProviders } from '../../test/render';
import { PilotControl } from './PilotControl';

const STATUS = (participant: boolean) => ({ participant, persona: participant ? 'kitchen' : null, screenshotMaxBytes: 5242880, screenshotTypes: ['image/jpeg', 'image/png'] });

describe('the Studio\'s "Send feedback" control (S-121)', () => {
  it('asks for the business, and sends feedback with the business, the screen and a screenshot', async () => {
    window.history.pushState({}, '', '/b/M1/kitchen/live?ticket=42');
    const calls = mockFetch(c => {
      if (c.url.endsWith('/api/v1/me/pilot?merchantId=M1')) return { body: STATUS(true) };
      if (c.url.endsWith('/api/v1/me/pilot/screenshots')) return { status: 201, body: { id: 'SHOT1', contentType: 'image/png', size: 4 } };
      if (c.url.endsWith('/api/v1/me/pilot/feedback')) return { status: 201, body: { id: 'F1', reference: 'UAT-1001' } };
      return undefined;
    });
    renderWithProviders(<PilotControl merchantId="M1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Send feedback' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('What happened?'), 'No sound for new orders.');
    await userEvent.upload(document.querySelector('input[type=file]') as HTMLInputElement, new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 's.png', { type: 'image/png' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }));
    expect(await within(dialog).findByText(/UAT-1001/)).toBeTruthy();
    expect(calls.find(c => c.url.endsWith('/screenshots'))!.body).toBeInstanceOf(FormData);
    expect(calls.find(c => c.url.endsWith('/feedback'))!.body).toMatchObject({ app: 'studio', merchantId: 'M1', route: '/b/M1/kitchen/live', screenshotId: 'SHOT1' });
  });

  it('shows nothing outside the pilot', async () => {
    const calls = mockFetch(c => (c.url.includes('/api/v1/me/pilot') ? { body: STATUS(false) } : undefined));
    renderWithProviders(<PilotControl merchantId="M1" />);
    await waitFor(() => expect(calls.length).toBe(1));
    expect(screen.queryByRole('button', { name: 'Send feedback' })).toBeNull();
  });
});
