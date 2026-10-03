import { useQuery } from '@tanstack/react-query';
import { PilotFeedback, feedbackContext, useLocale } from '@northline/ui';
import { PilotStatus, pilotStatusPath, sendPilotFeedback } from '@northline/client';
import { http } from '../../lib/http';

/** S-121: the Studio's "Send feedback" control, for members of a business that takes part in the pilot. */
export function PilotControl({ merchantId }: { merchantId: string }) {
  const { locale } = useLocale();
  const status = useQuery({ queryKey: ['pilot', 'status', merchantId], queryFn: () => http(pilotStatusPath(merchantId), {}, PilotStatus), staleTime: 5 * 60_000, retry: false });
  if (!status.data?.participant) return null;
  return (
    <PilotFeedback maxScreenshotBytes={status.data.screenshotMaxBytes}
      onSubmit={d => sendPilotFeedback(http, { app: 'studio', merchantId, ...d, context: feedbackContext(import.meta.env.VITE_NL_APP_VERSION as string | undefined, locale) })} />
  );
}
