import type { Locale } from '@northline/ui';
import { pageTitle } from './messages';
import { ScreenPending } from './ScreenPending';
import type { ScreenKey } from './screens';

/**
 * Route options of a screen that isn't built yet: its title and <ScreenPending>. A story replaces
 * `...pending('cart')` with its own `head`, `loader` and `component` (docs/CONSUMER_WEB_PLAN.md § Routes).
 */
export const pending = (screen: ScreenKey) => ({
  // not built yet: nothing for search engines to keep (S-63)
  head: ({ match }: { match: { context: { locale: Locale } } }) => ({ meta: [{ title: pageTitle(match.context.locale, screen) }, { name: 'robots', content: 'noindex' }] }),
  component: () => <ScreenPending screen={screen} />,
});
