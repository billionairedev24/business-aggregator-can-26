import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, screen, waitFor } from 'expo-router/testing-library';

import { FIXTURE_PROOF_PHOTO, seedOrder } from '../src/fixtures/shop';
import { setServices } from '../src/services';
import { start } from './support';

jest.mock('@stripe/stripe-react-native', () => ({}));

interface PickerMock {
  __queue: unknown[];
  __camera: { granted: boolean };
  launchCameraAsync: jest.Mock;
  launchImageLibraryAsync: jest.Mock;
  requestCameraPermissionsAsync: jest.Mock;
}
const picker = () => jest.requireMock('expo-image-picker') as PickerMock;
const photo = (n: number, extra: object = {}) => ({ uri: `file:///photos/p${n}.jpg`, mimeType: 'image/jpeg', fileSize: 120_000, width: 1200, height: 900, ...extra });

const HOME = { label: '1204 Example Ave, Sampleville', city: 'Sampleville', province: 'XA', street: '1204 Example Ave', postalCode: 'A1A 1A1', marketId: 'mkt-sampleville' };

afterEach(async () => {
  setServices(null);
  picker().__queue.length = 0;
  picker().__camera.granted = true;
  jest.clearAllMocks();
  await AsyncStorage.clear();
});

async function withOrder(url: string, extra: Parameters<typeof seedOrder>[4] = {}, wrap?: (f: typeof fetch) => typeof fetch) {
  const started = await start({ signedIn: true, store: { 'nl.location': JSON.stringify(HOME) }, url, wrap });
  seedOrder(started.server.shop, Date.now(), 'delivered', 'ord-1001', extra);
  await act(async () => {
    await started.services.queryClient.invalidateQueries();
  });
  return started;
}

describe('the proof-of-delivery photo on the delivered screen (mobile gaps part 1)', () => {
  it('shows the courier’s door photo from its short-lived link', async () => {
    const { server } = await withOrder('/orders/ord-1001/delivered');
    const image = await screen.findByTestId('proof-photo');
    expect(image.props.source).toEqual({ uri: FIXTURE_PROOF_PHOTO });
    expect(screen.getByLabelText('The courier’s photo of your order at your door')).toBeTruthy();
    expect(screen.getByText(/^proof-of-delivery photo · .* · at your door$/)).toBeTruthy();
    expect(server.calls.some((c) => c.path === '/me/orders/ord-1001/proof-photo' && c.signed)).toBe(true);
  });

  it('never shows a photo for a delivery with an ID check, nor for a PIN; asks for none then', async () => {
    const { server } = await withOrder('/orders/ord-1001/delivered', { idCheck: true });
    await screen.findByText(/^Delivered at /);
    await waitFor(() => expect(server.calls.some((c) => c.path === '/me/orders/ord-1001/proof-photo')).toBe(true));
    expect(await screen.findByTestId('proof-placeholder')).toBeTruthy();
    expect(screen.queryByTestId('proof-photo')).toBeNull();
    screen.unmount();
    setServices(null);
    const pin = await withOrder('/orders/ord-1001/delivered', { proof: 'pin' });
    expect(await screen.findByText(/^delivered with your PIN · /)).toBeTruthy();
    expect(pin.server.calls.some((c) => c.path.endsWith('/proof-photo'))).toBe(false);
  });

  it('keeps the placeholder when the link can’t be read (offline, 5xx)', async () => {
    const failing = (f: typeof fetch) =>
      (async (input: RequestInfo | URL, init?: RequestInit) =>
        String(input).endsWith('/proof-photo') ? new Response('{}', { status: 503 }) : f(input, init)) as typeof fetch;
    await withOrder('/orders/ord-1001/delivered', {}, failing);
    expect(await screen.findByTestId('proof-placeholder')).toBeTruthy();
    expect(screen.queryByTestId('proof-photo')).toBeNull();
  });
});

describe('photos on a problem report (mobile gaps part 1)', () => {
  it('adds up to 3 photos from the camera or the library and attaches them to the case', async () => {
    const { server } = await withOrder('/problem/order/ord-1001');
    fireEvent.press(await screen.findByRole('checkbox', { name: 'Kale & chard mix, $4.46' }));
    fireEvent.press(screen.getByRole('radio', { name: 'Damaged' }));
    picker().__queue.push(photo(1));
    fireEvent.press(screen.getByTestId('refund-photo-camera'));
    expect(await screen.findByLabelText('Photo 1')).toBeTruthy();
    expect(picker().requestCameraPermissionsAsync).toHaveBeenCalled();
    expect(picker().launchCameraAsync).toHaveBeenCalledWith(expect.objectContaining({ mediaTypes: ['images'], exif: false }));
    picker().__queue.push(photo(2, { mimeType: 'image/png', uri: 'file:///photos/p2.png' }));
    fireEvent.press(screen.getByTestId('refund-photo-library'));
    expect(await screen.findByLabelText('Photo 2')).toBeTruthy();
    picker().__queue.push(photo(3));
    fireEvent.press(screen.getByTestId('refund-photo-library'));
    expect(await screen.findByLabelText('Photo 3')).toBeTruthy();
    // the third is the last: no more buttons; removing one brings them back
    expect(screen.queryByTestId('refund-photo-library')).toBeNull();
    fireEvent.press(screen.getByTestId('refund-photo-remove-1'));
    expect(await screen.findByTestId('refund-photo-library')).toBeTruthy();
    expect(server.shop.uploads).toEqual(['up-1', 'up-2', 'up-3']);
    fireEvent.press(screen.getByTestId('refund-submit'));
    expect(await screen.findByText('Case RF-2201 · in review')).toBeTruthy();
    expect(server.shop.reports.at(-1)).toMatchObject({ attachmentIds: ['up-1', 'up-3'], reason: 'damaged' });
  });

  it('refuses what the api would: not JPEG/PNG, over 5 MB; and says when the camera is refused', async () => {
    const { server } = await withOrder('/problem/order/ord-1001');
    picker().__queue.push(photo(1, { mimeType: 'image/heic', uri: 'file:///p.heic' }));
    fireEvent.press(await screen.findByTestId('refund-photo-library'));
    expect(await screen.findByText('Add a JPEG or PNG photo.')).toBeTruthy();
    picker().__queue.push(photo(2, { fileSize: 6 * 1024 * 1024 }));
    fireEvent.press(screen.getByTestId('refund-photo-library'));
    expect(await screen.findByText('That photo is over 5 MB. Choose a smaller one.')).toBeTruthy();
    picker().__camera.granted = false;
    fireEvent.press(screen.getByTestId('refund-photo-camera'));
    expect(await screen.findByText('Northline can’t use the camera. Allow it in your phone’s settings, or choose a photo instead.')).toBeTruthy();
    expect(picker().launchCameraAsync).not.toHaveBeenCalled();
    expect(server.shop.uploads).toEqual([]);
  });

  it('shows the api’s refusal of an upload and drops the photo', async () => {
    const refusing = (f: typeof fetch) =>
      (async (input: RequestInfo | URL, init?: RequestInit) =>
        String(input).endsWith('/me/case-uploads')
          ? new Response(JSON.stringify({ errors: [{ field: 'file', rule: 'format', message: 'Attach JPG, PNG, HEIC or PDF files.' }] }), { status: 422, headers: { 'content-type': 'application/json' } })
          : f(input, init)) as typeof fetch;
    await withOrder('/problem/order/ord-1001', {}, refusing);
    picker().__queue.push(photo(1));
    fireEvent.press(await screen.findByTestId('refund-photo-library'));
    expect(await screen.findByText('Attach JPG, PNG, HEIC or PDF files.')).toBeTruthy();
    expect(screen.queryByLabelText('Photo 1')).toBeNull();
  });

  it('is in French', async () => {
    await AsyncStorage.setItem('nl.app.language', 'fr-CA');
    const started = await start({ signedIn: true, store: { 'nl.location': JSON.stringify(HOME), 'nl.app.language': 'fr-CA' }, url: '/problem/order/ord-1001' });
    seedOrder(started.server.shop, Date.now(), 'delivered');
    await act(async () => {
      await started.services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('Prendre une photo')).toBeTruthy();
    expect(screen.getByText('Choisir dans les photos')).toBeTruthy();
  });
});
