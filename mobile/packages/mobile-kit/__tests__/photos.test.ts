import { REPORT_PHOTO_TYPES, SCREENSHOT_MAX_BYTES, SCREENSHOT_TYPES, checkPhoto, photoForm, photoType, pilotApi, screenOf } from '../src/photos';

/** React Native's FormData keeps the `{uri, type, name}` part as given (Node's would stringify it). */
class NativeFormData {
  private parts = new Map<string, unknown>();
  append(k: string, v: unknown) {
    this.parts.set(k, v);
  }
  get(k: string) {
    return this.parts.get(k);
  }
}
const nodeFormData = global.FormData;
beforeAll(() => {
  (global as { FormData: unknown }).FormData = NativeFormData;
});
afterAll(() => {
  global.FormData = nodeFormData;
});

describe('photos from the apps', () => {
  it('takes the picker’s type, else the file name’s', () => {
    expect(photoType({ uri: 'file:///a', mimeType: 'image/PNG' })).toBe('image/png');
    expect(photoType({ uri: 'file:///a', mimeType: 'image/jpg' })).toBe('image/jpeg');
    expect(photoType({ uri: 'file:///x/IMG_1.JPEG?x=1' })).toBe('image/jpeg');
    expect(photoType({ uri: 'file:///x/a.heic' })).toBeNull();
  });

  it('refuses what the api would: not PNG/JPEG, over 5 MB', () => {
    expect(checkPhoto({ uri: 'a.png', mimeType: 'image/png', fileSize: 1000 }, SCREENSHOT_TYPES, SCREENSHOT_MAX_BYTES)).toBeNull();
    expect(checkPhoto({ uri: 'a.heic', mimeType: 'image/heic' }, SCREENSHOT_TYPES, SCREENSHOT_MAX_BYTES)).toBe('type');
    expect(checkPhoto({ uri: 'a.gif', mimeType: 'image/gif' }, REPORT_PHOTO_TYPES, SCREENSHOT_MAX_BYTES)).toBe('type');
    expect(checkPhoto({ uri: 'a.jpg', mimeType: 'image/jpeg', fileSize: SCREENSHOT_MAX_BYTES + 1 }, SCREENSHOT_TYPES, SCREENSHOT_MAX_BYTES)).toBe('size');
    // the size isn't always known: the api checks it again
    expect(checkPhoto({ uri: 'a.jpg', mimeType: 'image/jpeg' }, SCREENSHOT_TYPES, SCREENSHOT_MAX_BYTES)).toBeNull();
  });

  it('uploads as multipart `file` and sends feedback with the app and the screenshot', async () => {
    const posts: Array<{ path: string; options: { form?: FormData; json?: unknown } }> = [];
    const gets: string[] = [];
    const api = {
      get: jest.fn(async (path: string) => (gets.push(path), { participant: true })),
      post: jest.fn(async (path: string, options: { form?: FormData; json?: unknown } = {}) => (posts.push({ path, options }), { id: 'S1', reference: 'UAT-1001' })),
    };
    const courier = pilotApi(api as never, 'courier');
    await courier.status();
    await pilotApi(api as never, 'mobile').status();
    expect(gets).toEqual(['/me/pilot?app=courier', '/me/pilot']);
    await courier.screenshot({ uri: 'file:///shot.png', mimeType: 'image/png' });
    expect(posts[0]!.path).toBe('/me/pilot/screenshots');
    const file = posts[0]!.options.form?.get('file') as unknown as { type: string; name: string };
    expect(file).toMatchObject({ type: 'image/png', name: 'screenshot.png' });
    await courier.send({ category: 'bug', severity: 'minor', body: 'x', route: '/run', appVersion: '1', locale: 'en', platform: 'p', screenshotId: 'S1' });
    expect(posts[1]).toMatchObject({ path: '/me/pilot/feedback', options: { json: { app: 'courier', screenshotId: 'S1' } } });
    expect(photoForm({ uri: 'file:///b' }, 'photo-1').get('file')).toMatchObject({ name: 'photo-1.jpg', type: 'image/jpeg' });
  });

  it('keeps only the path of a screen', () => {
    expect(screenOf('/orders/1?token=abc#x')).toBe('/orders/1');
    expect(screenOf(undefined)).toBe('/');
  });
});
