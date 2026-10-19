import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { LegalForms } from '../features/onboarding/api';

/**
 * What the api serves at `GET …/onboarding/legal-forms` for four provinces — written by the server's
 * LegalFormsApiTest from the region data (`LEGAL_FORMS_FIXTURES_WRITE=1`), which also fails when this file and the api
 * disagree. Test data: the Studio code itself never names a province.
 */
export const LEGAL_FORMS = JSON.parse(readFileSync(resolve(__dirname, 'legal-forms.json'), 'utf8')) as Record<'AB' | 'BC' | 'ON' | 'QC', LegalForms>;

export const isLegalForms = (url: string) => /\/api\/v1\/merchants\/[^/]+\/onboarding\/legal-forms$/.test(url);
