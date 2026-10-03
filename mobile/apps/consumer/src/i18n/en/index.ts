import { account } from './account';
import { common } from './common';
import { journeyA } from './journeyA';
import { pilot } from './pilot';
import { screens } from './screens';
import { services } from './services';
import { shop } from './shop';

/**
 * The consumer app's English copy: one file per area so the journeys' stories (S-98 … S-101) edit their own file.
 * Design 01's words where it has them (exactly), ours otherwise; fr-CA has exactly these keys (a test checks). Place
 * names, times, prices and counts are parameters (region-neutral).
 */
export const en = { ...common, ...screens, ...journeyA, ...shop, ...services, ...account, ...pilot };
export type MessageKey = keyof typeof en;
