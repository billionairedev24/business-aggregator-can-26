import { createFileRoute } from '@tanstack/react-router';
import helpCss from '../features/help/help.css?url';
import { helpText } from '../features/help/messages';
import { HelpScreen } from '../features/help/HelpScreen';

/** Help (S-145): for guests and signed-in people alike, linked from every page's footer. Server-rendered and indexable. */
export const Route = createFileRoute('/help')({
  head: ({ match }) => {
    const t = helpText(match.context.locale);
    return {
      meta: [{ title: t('title') }, { name: 'description', content: t('description') }],
      links: [{ rel: 'stylesheet', href: helpCss }],
    };
  },
  component: HelpScreen,
});
