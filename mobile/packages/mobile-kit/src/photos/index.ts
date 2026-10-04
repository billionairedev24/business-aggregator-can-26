import type { ApiClient } from '../api/client';

/**
 * Photos the apps send (mobile gaps part 1): a screenshot on pilot feedback (S-121) and photos on a problem report
 * (S-99/S-60). Both apps pick them with `expo-image-picker` (the system photo picker, or the camera for a report) and
 * check them here before uploading; the api checks them again as it does the web's (declared type, magic bytes, a
 * readable image within S-104's pixel ceiling, the size).
 */

/** A picked image as `expo-image-picker` describes it (`ImagePickerAsset`, the parts used). */
export interface PickedImage {
  uri: string;
  mimeType?: string | null;
  fileSize?: number | null;
  fileName?: string | null;
  width?: number;
  height?: number;
}

export type PhotoProblem = 'type' | 'size';

/** The same limits as the web's feedback control and `POST /me/pilot/screenshots`. */
export const SCREENSHOT_TYPES = ['image/png', 'image/jpeg'] as const;
export const SCREENSHOT_MAX_BYTES = 5 * 1024 * 1024;
/** A problem report: up to 3 photos in the apps (the api takes up to 5 files on a case), JPEG or PNG. */
export const REPORT_PHOTO_TYPES = ['image/jpeg', 'image/png'] as const;
export const REPORT_PHOTO_MAX = 3;
export const REPORT_PHOTO_MAX_BYTES = 5 * 1024 * 1024;

/** The picked file's type: the picker's, else from the file name (`.png`, `.jpg`), else unknown. */
export function photoType(image: PickedImage): string | null {
  const declared = image.mimeType?.toLowerCase();
  if (declared) return declared === 'image/jpg' ? 'image/jpeg' : declared;
  const name = (image.fileName ?? image.uri).toLowerCase().split(/[?#]/)[0] ?? '';
  if (name.endsWith('.png')) return 'image/png';
  if (name.endsWith('.jpg') || name.endsWith('.jpeg')) return 'image/jpeg';
  return null;
}

/** Why the phone won't send this image (null: it may): not a PNG/JPEG, or over the size. */
export function checkPhoto(image: PickedImage, types: readonly string[], maxBytes: number): PhotoProblem | null {
  const type = photoType(image);
  if (!type || !types.includes(type)) return 'type';
  if (image.fileSize != null && image.fileSize > maxBytes) return 'size';
  return null;
}

/** A multipart body with the image as `file` (React Native's FormData streams it from its uri). */
export function photoForm(image: PickedImage, name: string): FormData {
  const type = photoType(image) ?? 'image/jpeg';
  const form = new FormData();
  form.append('file', { uri: image.uri, type, name: `${name}.${type === 'image/png' ? 'png' : 'jpg'}` } as unknown as Blob);
  return form;
}

export type FeedbackApp = 'mobile' | 'courier';
export type FeedbackCategory = 'bug' | 'confusing' | 'idea' | 'praise';
export type FeedbackSeverity = 'blocker' | 'major' | 'minor' | 'cosmetic';
export interface PilotStatus { participant: boolean; persona?: string | null }
export interface PilotFeedback {
  category: FeedbackCategory;
  severity: FeedbackSeverity;
  body: string;
  route: string;
  appVersion: string;
  locale: string;
  platform: string;
  screenshotId?: string;
}

/**
 * S-121 pilot feedback from an app (api `PilotFeedbackController`, the web's endpoints):
 *   GET  /me/pilot[?app=courier]  whether the button shows (the courier app: pilot couriers only)
 *   POST /me/pilot/screenshots    multipart `file` (PNG or JPEG, ≤ 5 MB) → {id}
 *   POST /me/pilot/feedback       {app, category, severity, body, route, appVersion, locale, platform, screenshotId?}
 */
export const pilotApi = (api: Pick<ApiClient, 'get' | 'post'>, app: FeedbackApp) => ({
  status: () => api.get<PilotStatus>(app === 'courier' ? '/me/pilot?app=courier' : '/me/pilot'),
  screenshot: (image: PickedImage) =>
    api.post<{ id: string; contentType: string; size: number }>('/me/pilot/screenshots', { form: photoForm(image, 'screenshot') }),
  send: (f: PilotFeedback) => api.post<{ id: string; reference: string }>('/me/pilot/feedback', { json: { app, ...f } }),
});

/** The screen as a path: no query string or fragment (they can hold tokens). */
export const screenOf = (pathname: string | undefined | null) => (pathname || '/').split(/[?#]/)[0] || '/';
