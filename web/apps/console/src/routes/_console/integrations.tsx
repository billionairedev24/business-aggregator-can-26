import { createFileRoute } from '@tanstack/react-router';
import { IntegrationsScreen } from '../../features/integrations/IntegrationsScreen';

/** API & webhooks (S-96): every business's API keys. */
export const Route = createFileRoute('/_console/integrations')({ component: IntegrationsScreen });
