import { useQuery } from '@tanstack/react-query';
import { PilotFeedback, feedbackContext, useLocale } from '@northline/ui';
import { http, PilotStatus, pilotStatusPath, sendPilotFeedback } from '@northline/client';

/** S-121: the consumer site's "Send feedback" control, for signed-in customers who take part in the pilot. */
export function PilotControl({ signedIn }: { signedIn: boolean }) {
  const { locale } = useLocale();
  const status = useQuery({ queryKey: ['pilot', 'status'], queryFn: () => http(pilotStatusPath(), {}, PilotStatus), enabled: signedIn, staleTime: 5 * 60_000, retry: false });
  if (!signedIn || !status.data?.participant) return null;
  return (
    <PilotFeedback maxScreenshotBytes={status.data.screenshotMaxBytes}
      onSubmit={d => sendPilotFeedback(http, { app: 'consumer', ...d, context: feedbackContext(import.meta.env.VITE_NL_APP_VERSION as string | undefined, locale) })} />
  );
}
