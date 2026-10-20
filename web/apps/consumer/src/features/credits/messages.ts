import { defineMessages, messagesFor, type Locale } from '@northline/ui';

/**
 * Photo credits (owner 2026-10-19, real photos): the stock photographs the site and the apps show, who took them, where
 * they come from and under which licence — generated from the photo library (web/packages/media/photos.json). Linked
 * from every page's footer. French: ours.
 */
const CREDITS = {
  en: {
    title: 'Photo credits · {brand}',
    description: 'The stock photographs on {brand}, their photographers and their licences.',
    heading: 'Photo credits',
    lede: 'The photographs of places, trades and goods on {brand} are stock photos used under their licences. Thank you to the photographers. Photos that businesses upload of their own work belong to them.',
    count: '{count, plural, one {# photograph} other {# photographs}}',
    none: 'No stock photographs are in use yet: the pictures on the site are drawings.',
    by: 'by {name}',
    byUnknown: 'photographer unknown',
    on: 'on {site}',
    licence: 'Licence: {licence}',
    page: 'Photo page',
    pageOf: 'Photo page of “{what}”',
  },
  fr: {
    title: 'Crédits photo · {brand}',
    description: 'Les photos d’archives de {brand}, leurs photographes et leurs licences.',
    heading: 'Crédits photo',
    lede: 'Les photos de lieux, de métiers et de produits sur {brand} sont des photos d’archives utilisées selon leurs licences. Merci aux photographes. Les photos que les entreprises publient de leur propre travail leur appartiennent.',
    count: '{count, plural, one {# photo} other {# photos}}',
    none: 'Aucune photo d’archives n’est encore utilisée : les images du site sont des dessins.',
    by: 'par {name}',
    byUnknown: 'photographe inconnu',
    on: 'sur {site}',
    licence: 'Licence : {licence}',
    page: 'Page de la photo',
    pageOf: 'Page de la photo « {what} »',
  },
};

export const useCreditsT = defineMessages(CREDITS);
export const creditsText = (locale: Locale) => messagesFor(CREDITS, locale);
