import { Link } from '@tanstack/react-router';
import { Compass } from '@phosphor-icons/react';
import { defineMessages } from '@northline/ui';
import { Standalone } from './Standalone';

const useT = defineMessages({
  en: { title: 'Page not found.', body: 'The link may be old, or the page has moved. Everything you run is in the Studio.', back: 'Back to Studio' },
  fr: { title: 'Page introuvable.', body: 'Le lien est peut-être ancien, ou la page a changé d’adresse. Tout ce que vous gérez se trouve dans le Studio.', back: 'Retour au Studio' },
});
export function NotFound() {
  const t = useT();
  return (
    <Standalone icon={<Compass size={28} />} tone="neutral">
      <h1>{t('title')}</h1>
      <p className="nl-muted">{t('body')}</p>
      <div className="nl-solo-actions"><Link to="/" className="btn btn-primary">{t('back')}</Link></div>
    </Standalone>
  );
}
