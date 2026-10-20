import type { en } from '../en';
import { account } from './account';
import { afterSale } from './afterSale';
import { aftercare } from './aftercare';
import { common } from './common';
import { hire } from './hire';
import { journeyA } from './journeyA';
import { light } from './light';
import { loyalty } from './loyalty';
import { pilot } from './pilot';
import { redesign } from './redesign';
import { screens } from './screens';
import { services } from './services';
import { shop } from './shop';
import { s159 } from './s159';

/** Canadian French: design/i18n-fr.js's wording where it has the phrase, ours otherwise. */
export const frCA: { [k in keyof typeof en]: string } = { ...common, ...screens, ...journeyA, ...shop, ...services, ...account, ...pilot, ...aftercare, ...afterSale, ...redesign, ...light, ...loyalty, ...hire, ...s159 };
