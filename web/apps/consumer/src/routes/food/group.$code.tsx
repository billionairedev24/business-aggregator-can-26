import { createFileRoute } from '@tanstack/react-router';
import foodCss from '../../features/food/food.css?url';
import { GroupScreen } from '../../features/food/GroupScreen';
import { pageTitle } from '../../features/shell/messages';

/** A group food order's share link (S-159): join, everyone's dishes, paying, then the one order's progress. */
export const Route = createFileRoute('/food/group/$code')({
  head: ({ match }) => ({ meta: [{ title: pageTitle(match.context.locale, 'foodGroup') }, { name: 'robots', content: 'noindex' }], links: [{ rel: 'stylesheet', href: foodCss }] }),
  component: Group,
});

function Group() {
  const { code } = Route.useParams();
  return <GroupScreen code={code} />;
}
