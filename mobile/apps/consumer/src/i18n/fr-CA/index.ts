import type { en } from '../en';
import { account } from './account';
import { aftercare } from './aftercare';
import { common } from './common';
import { journeyA } from './journeyA';
import { pilot } from './pilot';
import { screens } from './screens';
import { services } from './services';
import { shop } from './shop';

/** Canadian French: design/i18n-fr.js's wording where it has the phrase, ours otherwise. */
export const frCA: { [k in keyof typeof en]: string } = { ...common, ...screens, ...journeyA, ...shop, ...services, ...account, ...pilot, ...aftercare };
