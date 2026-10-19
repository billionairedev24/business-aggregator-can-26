import { CheckCircle } from '@phosphor-icons/react';
import { defineMessages } from '@northline/ui';
import { Standalone } from '../shell/Standalone';

const useT = defineMessages({
  en: {
    title: "You're done",
    body: 'Stripe is checking your ID and selfie. The business owner who sent the link sees the result in {brand} Studio — usually within a few minutes. You can close this page.',
    privacy: '{brand} never sees or keeps your ID or photos, only whether the check passed.',
  },
  fr: {
    title: "C'est terminé",
    body: "Stripe vérifie votre pièce d'identité et votre égoportrait. Le propriétaire qui vous a envoyé le lien verra le résultat dans {brand} Studio — généralement en quelques minutes. Vous pouvez fermer cette page.",
    privacy: "{brand} ne voit ni ne conserve votre pièce d'identité ni vos photos, seulement le résultat de la vérification.",
  },
});

/** `/identity/done` — where Stripe Identity sends owners who verified from an emailed link (S-22). Public: no account needed. */
export function IdentityDoneScreen() {
  const t = useT();
  return (
    <Standalone icon={<CheckCircle size={30} weight="fill" />}>
      <h1>{t('title')}</h1>
      <p>{t('body')}</p>
      <p className="nl-muted nl-small">{t('privacy')}</p>
    </Standalone>
  );
}
