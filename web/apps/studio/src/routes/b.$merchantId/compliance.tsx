import { createFileRoute } from '@tanstack/react-router';
import { ComplianceScreen } from '../../features/compliance/ComplianceScreen';
import { complianceQuery } from '../../features/compliance/api';

export const Route = createFileRoute('/b/$merchantId/compliance')({
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(complianceQuery(params.merchantId)); },
  component: ComplianceScreen,
});
