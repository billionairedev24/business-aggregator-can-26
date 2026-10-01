import { createFileRoute } from '@tanstack/react-router';
import accountCss from '../../features/account/account.css?url';
import { ProblemScreen } from '../../features/account/ProblemScreen';
import { pageTitle } from '../../features/shell/messages';

/** "Something's wrong" (S-60): report a problem with an order, a food order or a job. Personal: browser only. */
export const Route = createFileRoute('/account/problem/$kind/$id')({
  head: ({ match }) => ({
    meta: [{ title: pageTitle(match.context.locale, 'problem') }, { name: 'robots', content: 'noindex' }],
    links: [{ rel: 'stylesheet', href: accountCss }],
  }),
  component: ProblemRoute,
});

function ProblemRoute() {
  const { kind, id } = Route.useParams();
  return <ProblemScreen kind={kind} id={id} />;
}
