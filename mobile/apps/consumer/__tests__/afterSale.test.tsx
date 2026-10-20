import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, screen, waitFor } from 'expo-router/testing-library';

import { seedOrder } from '../src/fixtures/shop';
import { setServices } from '../src/services';
import { start } from './support';

jest.mock('@stripe/stripe-react-native', () => ({}));

/** S-151 on the phone: cancelling before a shop packs, the missed delivery's choice, and a return with a photo. */
interface PickerMock { __queue: unknown[]; __camera: { granted: boolean } }
const picker = () => jest.requireMock('expo-image-picker') as PickerMock;
const photo = (extra: object = {}) => ({ uri: 'file:///photos/item.jpg', mimeType: 'image/jpeg', fileSize: 120_000, width: 1200, height: 900, ...extra });
const HOME = { label: '1204 Example Ave, Sampleville', city: 'Sampleville', province: 'XA', street: '1204 Example Ave', postalCode: 'A1A 1A1', marketId: 'mkt-sampleville' };

afterEach(async () => {
  setServices(null);
  picker().__queue.length = 0;
  picker().__camera.granted = true;
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

async function withOrder(orderState: Parameters<typeof seedOrder>[2], url: string, extra: Parameters<typeof seedOrder>[4] = {}, french = false) {
  const store: Record<string, string> = { 'nl.location': JSON.stringify(HOME), ...(french ? { 'nl.app.language': 'fr-CA' } : {}) };
  if (french) await AsyncStorage.setItem('nl.app.language', 'fr-CA');
  const started = await start({ signedIn: true, store, url });
  seedOrder(started.server.shop, Date.now(), orderState, 'ord-1001', extra);
  await act(async () => {
    await started.services.queryClient.invalidateQueries();
  });
  return started;
}

describe('cancelling an order (S-151)', () => {
  it('asks why, then cancels while no shop has packed it', async () => {
    const { server } = await withOrder('placed', '/orders/ord-1001/track');
    fireEvent.press(await screen.findByTestId('cancel-open'));
    expect(screen.getByText('Cancel this order?')).toBeTruthy();
    fireEvent.press(screen.getByTestId('cancel-confirm'));
    expect(await screen.findByText('Choose a reason.')).toBeTruthy();
    fireEvent.press(screen.getByRole('radio', { name: 'I ordered by mistake' }));
    fireEvent.changeText(screen.getByTestId('cancel-note'), ' wrong address ');
    fireEvent.press(screen.getByTestId('cancel-confirm'));
    expect(await screen.findByText('You cancelled this order. The card holds were released.')).toBeTruthy();
    expect(server.calls.find((c) => c.method === 'POST' && c.path === '/me/orders/ord-1001/cancel')?.body).toEqual({ reason: 'ordered_by_mistake', note: 'wrong address' });
    expect(screen.queryByTestId('cancel-open')).toBeNull();
  });

  it('isn’t offered once a shop has packed', async () => {
    await withOrder('packing', '/orders/ord-1001/track');
    expect(await screen.findByText('Order NL-1001')).toBeTruthy();
    expect(screen.queryByTestId('cancel-open')).toBeNull();
  });
});

describe('a missed delivery (S-151)', () => {
  // a fresh one per test: the fixture backend changes it
  const failure = () => ({ failure: { reason: 'absent', failedAt: Date.now() - 60_000, choice: null } });

  it('says why and sends it out again on the next run', async () => {
    const { server } = await withOrder('delivery_failed', '/orders/ord-1001/track', failure());
    expect(await screen.findByText('We couldn’t deliver your order.')).toBeTruthy();
    expect(screen.getByText('The courier came by but nobody answered.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('failed-redeliver-button'));
    expect(await screen.findByText('It goes out again on the next run.')).toBeTruthy();
    expect(server.calls.find((c) => c.path === '/me/orders/ord-1001/delivery-failure')?.body).toEqual({ choice: 'redeliver' });
  });

  it('or refunds it', async () => {
    await withOrder('delivery_failed', '/orders/ord-1001/track', failure());
    fireEvent.press(await screen.findByTestId('failed-refund-button'));
    expect(await screen.findByText('You chose a refund after the missed delivery. It goes back to your card.')).toBeTruthy();
  });

  it('is in French', async () => {
    await withOrder('delivery_failed', '/orders/ord-1001/track', failure(), true);
    expect(await screen.findByText('Nous n’avons pas pu livrer votre commande.')).toBeTruthy();
    expect(screen.getByText('La livrer à la prochaine tournée')).toBeTruthy();
    expect(screen.getByText('Me rembourser')).toBeTruthy();
  });
});

describe('returns (S-151)', () => {
  it('checks the request, then sends one shop’s items, the reason and the photo', async () => {
    const { server } = await withOrder('delivered', '/orders/ord-1001/delivered');
    fireEvent.press(await screen.findByTestId('return-open'));
    fireEvent.press(screen.getByTestId('return-send'));
    expect(await screen.findByText('Choose what you’re returning.')).toBeTruthy();
    expect(screen.getByText('Add a photo of the item.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('return-line-li-1'));
    fireEvent.press(screen.getByTestId('return-line-li-2'));
    fireEvent.press(screen.getByRole('radio', { name: 'It arrived damaged' }));
    fireEvent.press(screen.getByTestId('return-send'));
    expect(await screen.findByText('Return items from one shop at a time.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('return-line-li-2'));
    // only images under 5 MB
    picker().__queue.push(photo({ mimeType: 'application/pdf', uri: 'file:///x.pdf' }));
    fireEvent.press(screen.getByTestId('return-photo-library'));
    expect(await screen.findByText('Use a JPG, PNG or WebP image under 5 MB.')).toBeTruthy();
    picker().__queue.push(photo());
    fireEvent.press(screen.getByTestId('return-photo-camera'));
    expect(await screen.findByLabelText('Photo added')).toBeTruthy();
    fireEvent.press(screen.getByTestId('return-send'));
    await waitFor(() => expect(server.shop.orders.get('ord-1001')?.returns).toHaveLength(1));
    const sent = server.calls.find((c) => c.method === 'POST' && c.path === '/me/orders/ord-1001/returns')?.body;
    expect(sent).toMatchObject({ lineIds: ['li-1'], reason: 'damaged' });
    expect(sent?.photo).toBeTruthy();
    expect(await screen.findByText(/^Waiting for /)).toBeTruthy();
    // withdrawn from the list
    fireEvent.press(screen.getByTestId(`return-withdraw-${server.shop.orders.get('ord-1001')!.returns![0]!.id}`));
    expect(await screen.findByText('Withdrawn')).toBeTruthy();
  });

  it('asks an agent about a declined return', async () => {
    const declined = {
      id: 'ret-9', ref: 'RT-9', orderId: 'ord-1001', merchantId: 'm-bakery', merchantName: 'A shop', state: 'declined', reason: 'damaged', note: null,
      method: null, declineReason: 'It was used', amountCents: 900, lines: [{ lineId: 'li-1', title: 'Sourdough', qty: 1, amountCents: 900 }],
      canWithdraw: false, canEscalate: true, photo: true,
    };
    const { server } = await withOrder('delivered', '/orders/ord-1001/delivered', { returns: [declined] });
    expect(await screen.findByText('A shop declined it')).toBeTruthy();
    expect(screen.getByText('The shop said: “It was used”')).toBeTruthy();
    fireEvent.press(screen.getByTestId('return-escalate-ret-9'));
    fireEvent.press(screen.getByTestId('return-send-ret-9'));
    expect(await screen.findByText('Tell the agent why the return should be accepted.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('return-statement-ret-9'), 'It arrived torn');
    fireEvent.press(screen.getByTestId('return-send-ret-9'));
    expect(await screen.findByText('With a Northline agent')).toBeTruthy();
    expect(server.calls.find((c) => c.path === '/me/returns/ret-9/escalate')?.body).toEqual({ statement: 'It arrived torn' });
  });
});
