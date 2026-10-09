import AsyncStorage from '@react-native-async-storage/async-storage';
import { fireEvent, screen, waitFor } from 'expo-router/testing-library';

import { screenOf } from '../src/api/pilot';
import { setServices } from '../src/services';
import { start } from './support';

jest.mock('@stripe/stripe-react-native', () => ({}));

afterEach(async () => {
  setServices(null);
  await AsyncStorage.clear();
});

interface Sent { url: string; body: Record<string, unknown> }
/** The pilot endpoints answered here; everything else goes to the fixture backend. */
function pilot(participant: boolean, sent: Sent[]) {
  return (f: typeof fetch) =>
    (async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = String(input);
      const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
      if (url.endsWith('/me/pilot')) return json(200, { participant, persona: participant ? 'customer' : null, screenshotMaxBytes: 5242880, screenshotTypes: [] });
      if (url.endsWith('/me/pilot/screenshots')) {
        sent.push({ url, body: {} });
        return json(201, { id: 'SHOT1', contentType: 'image/png', size: 1000 });
      }
      if (url.endsWith('/me/pilot/feedback')) {
        sent.push({ url, body: JSON.parse(String(init.body)) as Record<string, unknown> });
        return json(201, { id: 'F1', reference: 'UAT-1003' });
      }
      return f(input, init);
    }) as typeof fetch;
}

describe('pilot feedback in the app (S-121)', () => {
  it('shows the Feedback button to pilot customers and sends what they wrote with the screen', async () => {
    const sent: Sent[] = [];
    await start({ signedIn: true, url: '/wallet', wrap: pilot(true, sent) });
    fireEvent.press(await screen.findByTestId('pilot-feedback'));
    expect(await screen.findByText('Send feedback')).toBeTruthy();
    fireEvent.press(screen.getByTestId('pilot-c-confusing'));
    fireEvent.press(screen.getByTestId('pilot-s-major'));
    fireEvent.press(screen.getByTestId('pilot-send'));
    expect(await screen.findByText('Tell us what happened, in 1 to 4,000 characters.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('pilot-body'), 'Points balance looks wrong.');
    fireEvent.press(screen.getByTestId('pilot-send'));
    expect(await screen.findByText('Thank you — we got it as UAT-1003.')).toBeTruthy();
    expect(sent[0]!.body).toMatchObject({ app: 'mobile', category: 'confusing', severity: 'major', body: 'Points balance looks wrong.', route: '/wallet', locale: 'en' });
  }, 30_000);

  it('attaches a screenshot picked from the photo library (mobile gaps part 1), PNG or JPEG under 5 MB', async () => {
    const sent: Sent[] = [];
    const picker = jest.requireMock('expo-image-picker') as { __queue: unknown[]; launchImageLibraryAsync: jest.Mock };
    await start({ signedIn: true, url: '/wallet', wrap: pilot(true, sent) });
    fireEvent.press(await screen.findByTestId('pilot-feedback'));
    picker.__queue.push({ uri: 'file:///shots/a.gif', mimeType: 'image/gif', fileSize: 1000 });
    fireEvent.press(await screen.findByTestId('pilot-shot-add'));
    expect(await screen.findByText('Add a PNG or JPEG image.')).toBeTruthy();
    picker.__queue.push({ uri: 'file:///shots/b.png', mimeType: 'image/png', fileSize: 6 * 1024 * 1024 });
    fireEvent.press(screen.getByTestId('pilot-shot-add'));
    expect(await screen.findByText('That image is over 5 MB. Choose a smaller one.')).toBeTruthy();
    picker.__queue.push({ uri: 'file:///shots/c.png', mimeType: 'image/png', fileSize: 400_000 });
    fireEvent.press(screen.getByTestId('pilot-shot-add'));
    expect(await screen.findByTestId('pilot-shot')).toBeTruthy();
    expect(picker.launchImageLibraryAsync).toHaveBeenLastCalledWith(expect.objectContaining({ mediaTypes: ['images'], exif: false }));
    expect(screen.getByText(/^Sent with it: your screenshot, the screen \(\/wallet\)/)).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('pilot-body'), 'The chart overlaps.');
    fireEvent.press(screen.getByTestId('pilot-send'));
    expect(await screen.findByText('Thank you — we got it as UAT-1003.')).toBeTruthy();
    expect(sent.map((s) => s.url.replace(/^.*\/api\/v1/, ''))).toEqual(['/me/pilot/screenshots', '/me/pilot/feedback']);
    expect(sent[1]!.body).toMatchObject({ app: 'mobile', screenshotId: 'SHOT1', body: 'The chart overlaps.' });
  }, 30_000);

  it('shows nothing to people outside the pilot', async () => {
    await start({ signedIn: true, url: '/wallet', wrap: pilot(false, []) });
    await waitFor(() => expect(screen.queryByTestId('pilot-feedback')).toBeNull());
  });

  it('keeps no query string in the screen', () => {
    expect(screenOf('/orders/1?token=abc#x')).toBe('/orders/1');
    expect(screenOf(undefined)).toBe('/');
  });
});
