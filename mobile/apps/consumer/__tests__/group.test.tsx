import AsyncStorage from '@react-native-async-storage/async-storage';
import { fireEvent, screen, waitFor } from 'expo-router/testing-library';
import { Share } from 'react-native';

import { FIXTURE_GROUP_CODE } from '../src/fixtures/group';
import { setServices } from '../src/services';
import { start } from './support';

afterEach(async () => {
  setServices(null);
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

describe('group food orders in the app (S-159)', () => {
  it('a guest asks to sign in first', async () => {
    await start({ welcomed: true, url: `/group/${FIXTURE_GROUP_CODE}` });
    expect(await screen.findByText('Sign in to join')).toBeTruthy();
  });

  it('joins from the code, adds a dish with its choice, sees the host’s dishes, and shares the link', async () => {
    const share = jest.spyOn(Share, 'share').mockResolvedValue({ action: Share.sharedAction });
    const { server } = await start({ signedIn: true, url: '/group' });
    fireEvent.changeText(await screen.findByTestId('group-code-field'), FIXTURE_GROUP_CODE.toLowerCase());
    fireEvent.press(screen.getByRole('button', { name: 'Open' }));
    expect(await screen.findByText('Hana invited you to a group order at Sample Kitchen')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Join the group order' }));
    expect(await screen.findByText('1× Spring rolls · $8.00')).toBeTruthy();
    expect(screen.getByText('You')).toBeTruthy();
    expect(screen.getByText('Everyone pays their share · Old Town')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Add dishes' }));
    fireEvent.press(await screen.findByRole('radio', { name: 'Large +$3.00' }));
    fireEvent.press(screen.getByRole('button', { name: 'Add Pho tai · $19.00' }));
    expect(await screen.findByText('1× Pho tai (Large) · $19.00')).toBeTruthy();
    expect(server.calls.find((c) => c.method === 'POST' && c.path === '/me/group-orders/grp-1/items')?.body).toEqual({ itemId: 'd-pho', qty: 1, optionIds: ['o-lg'] });
    fireEvent.press(screen.getByRole('button', { name: 'Share the link' }));
    expect(share).toHaveBeenCalledWith({ message: expect.stringContaining('http://localhost:3000/food/group/7Q2M-K4XP') });
  });

  it('pays one’s share once the host has locked the order (the api’s stand-in: nothing to collect)', async () => {
    const { server } = await start({ signedIn: true, url: `/group/${FIXTURE_GROUP_CODE}` });
    fireEvent.press(await screen.findByRole('button', { name: 'Join the group order' }));
    await screen.findByText('You');
    // the host locks: each person's share is priced
    const g = server.group.group;
    g.state = 'locked';
    g.members.forEach((m) => (m.share = { subtotalCents: m.subtotalCents, deliveryFeeCents: 150, serviceFeeCents: 64, feeTaxCents: 11, taxCents: 40, tipCents: 0, totalCents: m.subtotalCents + 265, state: 'due', paidBy: null }));
    const me = g.members.at(-1)!;
    me.items.push({ id: 'i-9', itemId: 'd-rolls', title: 'Spring rolls', qty: 1, unitCents: 800, totalCents: 800, choices: [] });
    me.subtotalCents = 800;
    me.share!.subtotalCents = 800;
    me.share!.totalCents = 1065;
    expect(await screen.findByText('The host locked the order.', {}, { timeout: 8000 })).toBeTruthy();
    fireEvent.press(await screen.findByRole('button', { name: 'Pay my share · $10.65' }));
    await waitFor(() => expect(me.share!.state).toBe('authorized'));
    expect(await screen.findByText('Paid')).toBeTruthy();
    expect(server.calls.filter((c) => c.path.endsWith('/shares/acct-ada/pay'))).toHaveLength(1);
  });

  it('an unknown code says so', async () => {
    await start({ signedIn: true, url: '/group/2222-2222' });
    expect(await screen.findByText('That code doesn’t match any group order.')).toBeTruthy();
  });
});
