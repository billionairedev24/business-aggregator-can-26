import { z } from 'zod';
import { ValidationError, type RequestOptions } from './http';

/**
 * S-121 pilot feedback (api `PilotFeedbackController`), shared by the Studio, the consumer web and the console:
 *   GET  /api/v1/me/pilot[?merchantId=]   whether the "Send feedback" control shows
 *   POST /api/v1/me/pilot/screenshots     multipart "file" → {id}
 *   POST /api/v1/me/pilot/feedback        {app, category, severity, body, route, appVersion, locale, platform, screenshotId?, merchantId?}
 * Each app passes its own `http` (the console's adds the role view header).
 */
export type PilotHttp = <T>(path: string, opts?: RequestOptions, schema?: z.ZodType<T>) => Promise<T>;

export const PilotStatus = z.object({
  participant: z.boolean(), persona: z.string().nullish(), screenshotMaxBytes: z.number(), screenshotTypes: z.array(z.string()),
});
export type PilotStatus = z.infer<typeof PilotStatus>;

export const pilotStatusPath = (merchantId?: string) =>
  merchantId ? `/api/v1/me/pilot?merchantId=${encodeURIComponent(merchantId)}` : '/api/v1/me/pilot';

export interface PilotFeedbackRequest {
  app: 'studio' | 'consumer' | 'console' | 'mobile';
  merchantId?: string;
  category: string;
  severity: string;
  body: string;
  screenshot?: Blob;
  context: { route: string; appVersion: string; locale: string; platform: string };
}

/** Uploads the screenshot (if any), then sends the note; resolves with its reference ("UAT-1001"). */
export async function sendPilotFeedback(http: PilotHttp, r: PilotFeedbackRequest): Promise<{ id: string; reference: string }> {
  let screenshotId: string | undefined;
  try {
    if (r.screenshot) {
      const form = new FormData();
      form.append('file', r.screenshot, r.screenshot.type === 'image/jpeg' ? 'screenshot.jpg' : 'screenshot.png');
      screenshotId = (await http('/api/v1/me/pilot/screenshots', { method: 'POST', body: form }, z.object({ id: z.string() }))).id;
    }
    return await http('/api/v1/me/pilot/feedback', {
      method: 'POST',
      body: { app: r.app, merchantId: r.merchantId, category: r.category, severity: r.severity, body: r.body, ...r.context, screenshotId },
    }, z.object({ id: z.string(), reference: z.string() }));
  } catch (e) {
    // a 422 carries one message per field: show the first, as the form has no per-field place for server rules
    if (e instanceof ValidationError) throw new Error(Object.values(e.byField())[0] ?? e.message);
    throw e;
  }
}
