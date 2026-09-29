import { Link } from '@tanstack/react-router';
import { defineMessages } from '@northline/ui';

const useT = defineMessages({ en: { title: 'Page not found.', back: 'Back to Studio' }, fr: { title: 'Page introuvable.', back: 'Retour au Studio' } });
export function NotFound() {
  const t = useT();
  return <div style={{ padding: 48 }}><h1>{t('title')}</h1><Link to="/">{t('back')}</Link></div>;
}
