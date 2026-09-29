import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { HelpScreen } from '../../features/help/HelpScreen';

/**
 * `?tab=home|cases|new|status` · `?topic=<case topic>` opens Contact support with the topic chosen (onboarding's
 * "Need help with a document?" links to `?topic=verification`) · `?case=<id>` opens a case's conversation.
 */
export const Route = createFileRoute('/b/$merchantId/help')({
  validateSearch: z.object({
    tab: z.enum(['home', 'cases', 'new', 'status']).optional().catch(undefined),
    topic: z.string().optional(),
    case: z.string().optional(),
  }),
  component: function HelpRoute() {
    const search = Route.useSearch();
    const navigate = Route.useNavigate();
    return <HelpScreen search={search} onNavigate={next => void navigate({ search: next })} />;
  },
});
