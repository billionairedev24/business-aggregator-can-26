import { createFileRoute } from '@tanstack/react-router';
import homeCss from '../features/home/home.css?url';
import loyaltyCss from '../features/loyalty/loyalty.css?url';
// S-188: the hero band's display face (Archivo, latin), preloaded so the headline draws in it at once (no swap, no shift)
import archivoLatin from '@northline/tokens/fonts/archivo-latin-wdth-normal.woff2?url';
import { HomeScreen } from '../features/home/HomeScreen';
import { seo } from '../lib/seo';
import { siteJsonLd } from '../lib/structuredData';
import { brand } from '@northline/tokens';

const DESCRIPTION = {
  en: 'Groceries and goods on a shared run, hot food in 30 minutes, verified providers for anything at home — every job paid into escrow, every seller vetted.',
  fr: 'Épicerie et produits sur une tournée partagée, repas chauds en 30 minutes, prestataires vérifiés pour tout à la maison — chaque service payé en fiducie, chaque vendeur contrôlé.',
};

/** Home (S-46): search-first hero, Services / Shop / Food entry points. The header has no search here. */
export const Route = createFileRoute('/')({
  head: ({ match }) => {
    const { locale, config } = match.context;
    const tags = seo(match.context, {
      title: locale === 'fr' ? `${brand.name} · Tous les commerçants de confiance, à un geste de votre porte` : `${brand.name} · Every trusted local, one tap from the door`,
      description: DESCRIPTION[locale], path: '/', jsonLd: siteJsonLd(config.siteOrigin, config.legalEntity),
    });
    return { ...tags, links: [...tags.links, { rel: 'preload', href: archivoLatin, as: 'font', type: 'font/woff2', crossOrigin: 'anonymous' }, { rel: 'stylesheet', href: homeCss }, { rel: 'stylesheet', href: loyaltyCss }] };
  },
  component: HomeScreen,
});
