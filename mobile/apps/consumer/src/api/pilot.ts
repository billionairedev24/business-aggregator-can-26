import type { ApiClient } from '@northline/mobile-kit';

/**
 * S-121 pilot feedback from the consumer app (api `PilotFeedbackController`, the same endpoints as the web):
 *   GET  /me/pilot            whether the "Feedback" button shows (pilot participants only)
 *   POST /me/pilot/feedback   {app: mobile, category, severity, body, route, appVersion, locale, platform}
 * No screenshot from the app yet (it would need a native screen-capture module; DECISIONS S-121).
 */
export interface PilotStatus { participant: boolean; persona?: string | null }
export type FeedbackCategory = 'bug' | 'confusing' | 'idea' | 'praise';
export type FeedbackSeverity = 'blocker' | 'major' | 'minor' | 'cosmetic';
export interface PilotFeedback {
  category: FeedbackCategory;
  severity: FeedbackSeverity;
  body: string;
  route: string;
  appVersion: string;
  locale: string;
  platform: string;
}

export const pilotApi = (api: ApiClient) => ({
  status: () => api.get<PilotStatus>('/me/pilot'),
  send: (f: PilotFeedback) => api.post<{ id: string; reference: string }>('/me/pilot/feedback', { json: { app: 'mobile', ...f } }),
});

/** The screen as a path: no query string or fragment (they can hold tokens). */
export const screenOf = (pathname: string | undefined | null) => (pathname || '/').split(/[?#]/)[0] || '/';
