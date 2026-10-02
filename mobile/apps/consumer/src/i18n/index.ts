import { createI18n } from '@northline/mobile-kit';

import { en } from './en';
import { frCA } from './fr-CA';

export const { I18nProvider, useI18n, translator } = createI18n({ en, 'fr-CA': frCA });
export type { MessageKey } from './en';
