import { createFileRoute } from '@tanstack/react-router';
import { ApplicationPage } from '../../features/verify/ApplicationPage';

/** One application (S-177): `/verification/<merchant id>`. */
export const Route = createFileRoute('/_console/verification_/$applicationId')({ component: ApplicationPage });
