import { createFileRoute } from '@tanstack/react-router';
import { TeamScreen } from '../../features/team/TeamScreen';

/** Team, roles & audit (S-96). */
export const Route = createFileRoute('/_console/team')({ component: TeamScreen });
