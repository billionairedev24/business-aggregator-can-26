import { createFileRoute } from '@tanstack/react-router';
import creditsCss from '../features/credits/credits.css?url';
import { creditsText } from '../features/credits/messages';
import { CreditsScreen } from '../features/credits/CreditsScreen';

/** Photo credits: the stock photographs of the photo library, from every page's footer. Server-rendered and indexable. */
export const Route = createFileRoute('/credits')({
  head: ({ match }) => {
    const t = creditsText(match.context.locale);
    return {
      meta: [{ title: t('title') }, { name: 'description', content: t('description') }],
      links: [{ rel: 'stylesheet', href: creditsCss }],
    };
  },
  component: () => <CreditsScreen />,
});
