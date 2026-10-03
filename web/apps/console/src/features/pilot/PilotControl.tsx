import { useQuery } from '@tanstack/react-query';
import { PilotFeedback, feedbackContext, useLocale } from '@northline/ui';
import { PilotStatus, pilotStatusPath, sendPilotFeedback } from '@northline/client';
import { http } from '../../lib/http';

/** S-121: the console's "Send feedback" control, for staff who take part in the pilot (persona `staff`). */
export function PilotControl() {
  const { locale } = useLocale();
  const status = useQuery({ queryKey: ['pilot', 'status'], queryFn: () => http(pilotStatusPath(), {}, PilotStatus), staleTime: 5 * 60_000, retry: false });
  if (!status.data?.participant) return null;
  return (
    <PilotFeedback maxScreenshotBytes={status.data.screenshotMaxBytes}
      onSubmit={d => sendPilotFeedback(http, { app: 'console', ...d, context: feedbackContext(import.meta.env.VITE_NL_APP_VERSION as string | undefined, locale) })} />
  );
}
