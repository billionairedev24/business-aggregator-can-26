import { createFileRoute } from '@tanstack/react-router';
import homeCss from '../features/home/home.css?url';
import { HomeScreen } from '../features/home/HomeScreen';
import { seo } from '../lib/seo';
import { siteJsonLd } from '../lib/structuredData';

const DESCRIPTION = {
  en: 'Groceries and goods on a shared run, hot food in 30 minutes, verified providers for anything at home — every job paid into escrow, every seller vetted.',
  fr: 'Épicerie et produits sur une tournée partagée, repas chauds en 30 minutes, prestataires vérifiés pour tout à la maison — chaque service payé en fiducie, chaque vendeur contrôlé.',
};

/** Home (S-46): search-first hero, Services / Shop / Food entry points. The header has no search here. */
export const Route = createFileRoute('/')({
  head: ({ match }) => {
    const { locale, config } = match.context;
    const tags = seo(match.context, {
      title: locale === 'fr' ? 'Northline · Tous les commerçants de confiance, à un geste de votre porte' : 'Northline · Every trusted local, one tap from the door',
      description: DESCRIPTION[locale], path: '/', jsonLd: siteJsonLd(config.siteOrigin, config.legalEntity),
    });
    return { ...tags, links: [...tags.links, { rel: 'stylesheet', href: homeCss }] };
  },
  component: HomeScreen,
});
