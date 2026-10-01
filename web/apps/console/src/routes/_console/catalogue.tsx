import { createFileRoute } from '@tanstack/react-router';
import { TaxonomyScreen } from '../../features/taxonomy/TaxonomyScreen';

/** Catalogue taxonomy (S-94): categories, regulators by province, category limits, businesses' suggestions. */
export const Route = createFileRoute('/_console/catalogue')({ component: TaxonomyScreen });
