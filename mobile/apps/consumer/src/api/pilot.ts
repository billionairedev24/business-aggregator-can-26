/**
 * S-121 pilot feedback from the consumer app: the api calls live in `@northline/mobile-kit` (shared with the courier
 * app since mobile gaps part 1) — `GET /me/pilot`, `POST /me/pilot/screenshots`, `POST /me/pilot/feedback` with
 * `app: mobile`.
 */
export {
  pilotApi,
  screenOf,
  type FeedbackCategory,
  type FeedbackSeverity,
  type PilotFeedback,
  type PilotStatus,
} from '@northline/mobile-kit';
