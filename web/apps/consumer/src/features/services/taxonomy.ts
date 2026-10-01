import type { Locale } from '@northline/ui';
import { localName, type Names, type ServiceKind } from './api';

/**
 * How a category reads on the Services screens. `catalogue.categories.name_i18n` is English-only (the seed carries no
 * French and `seedCategories` rewrites the column), so the French names of the service taxonomy live here until the
 * table carries them — the same approach onboarding took for group notes (docs/DECISIONS.md, S-53). Keys are the
 * category slugs (the last part of the id).
 */
export const FR_NAMES: Record<string, string> = {
  // groups
  automotive: 'Automobile', 'home-trades': 'Métiers du bâtiment', 'cleaning-and-property': 'Nettoyage et propriété',
  'events-and-hospitality': 'Événements et accueil', 'personal-care-and-wellness': 'Soins personnels et bien-être', pets: 'Animaux',
  'education-and-coaching': 'Éducation et coaching', professional: 'Services professionnels', 'tech-and-digital': 'Technologie et numérique',
  'childcare-and-family': 'Garde d’enfants et famille',
  // automotive
  'mobile-mechanic': 'Mécanicien mobile', 'oil-change-and-fluids': 'Vidange et liquides', 'brakes-and-suspension': 'Freins et suspension',
  'tire-change-and-storage': 'Changement et entreposage de pneus', 'windshield-repair': 'Réparation de pare-brise', detailing: 'Esthétique automobile',
  'battery-boost-and-replacement': 'Survoltage et remplacement de batterie', 'pre-purchase-inspection': 'Inspection avant achat',
  'towing-and-roadside': 'Remorquage et assistance routière', 'ev-charger-install': 'Installation de borne de recharge',
  // home trades
  plumber: 'Plombier', electrician: 'Électricien', 'gas-fitter': 'Monteur d’installations au gaz', 'hvac-and-furnace': 'CVC et fournaise',
  'hot-water-tank': 'Chauffe-eau', 'drywall-and-taping': 'Cloisons sèches et joints', painting: 'Peinture', 'flooring-and-tile': 'Planchers et carrelage',
  'carpentry-and-finishing': 'Menuiserie et finition', roofing: 'Toiture', eavestrough: 'Gouttières', 'siding-and-exterior': 'Revêtement et extérieur',
  'fencing-and-decks': 'Clôtures et terrasses', 'concrete-and-paving': 'Béton et pavage', 'foundation-and-waterproofing': 'Fondations et imperméabilisation',
  handyman: 'Homme à tout faire', 'appliance-repair': 'Réparation d’électroménagers', 'garage-doors': 'Portes de garage', locksmith: 'Serrurier',
  'home-inspector': 'Inspecteur en bâtiment',
  // cleaning & property
  'house-cleaning': 'Ménage', 'move-in-move-out-clean': 'Ménage d’emménagement / de déménagement', 'carpet-and-upholstery': 'Tapis et meubles rembourrés',
  'window-cleaning': 'Lavage de vitres', 'pressure-washing': 'Lavage à pression', 'junk-removal': 'Enlèvement de rebuts', movers: 'Déménageurs',
  'snow-removal': 'Déneigement', 'lawn-and-yard': 'Pelouse et cour', landscaping: 'Aménagement paysager', 'tree-service': 'Élagage et abattage',
  'pest-control': 'Extermination', 'duct-cleaning': 'Nettoyage de conduits', 'pool-and-hot-tub-service': 'Entretien de piscine et spa',
  'property-management': 'Gestion immobilière',
  // events & hospitality
  'cocktail-and-mocktail-bar': 'Bar à cocktails', bartender: 'Barman', 'event-planner': 'Organisateur d’événements', 'wedding-planner': 'Organisateur de mariage',
  dj: 'DJ', 'live-music': 'Musique live', photographer: 'Photographe', videographer: 'Vidéaste', 'photo-booth': 'Photomaton', florist: 'Fleuriste',
  'balloon-and-decor': 'Ballons et décor', 'rentals-tents-tables-chairs': 'Location · tentes, tables, chaises', 'catering-server-staff': 'Personnel de service traiteur',
  'mc-host': 'Animateur / maître de cérémonie', 'kids-party-entertainer': 'Animateur de fêtes d’enfants',
  // personal care & wellness
  'barber-and-hair': 'Coiffure', 'mobile-hair-and-makeup': 'Coiffure et maquillage à domicile', 'nail-technician': 'Technicienne en ongles',
  esthetician: 'Esthéticienne', 'massage-therapist': 'Massothérapeute', 'personal-trainer': 'Entraîneur personnel', 'yoga-pilates-instructor': 'Professeur de yoga / pilates',
  nutritionist: 'Nutritionniste', 'registered-dietitian': 'Diététiste', physiotherapist: 'Physiothérapeute', doula: 'Doula', 'home-care-aide': 'Aide à domicile',
  // pets
  'dog-walker': 'Promeneur de chiens', 'pet-sitter': 'Gardien d’animaux', 'dog-grooming': 'Toilettage de chiens', 'dog-training': 'Dressage de chiens', 'pet-taxi': 'Taxi pour animaux',
  // education & coaching
  'tutor-k-12': 'Tuteur · primaire et secondaire', 'tutor-post-secondary': 'Tuteur · postsecondaire', 'music-lessons': 'Cours de musique',
  'language-lessons': 'Cours de langue', 'driving-instructor': 'Moniteur de conduite', 'swim-instructor': 'Moniteur de natation', 'test-prep': 'Préparation aux examens',
  'career-coach': 'Coach de carrière', 'life-coach': 'Coach de vie',
  // professional
  'real-estate-agent': 'Courtier immobilier', 'mortgage-broker': 'Courtier hypothécaire', 'accountant-bookkeeper': 'Comptable / teneur de livres',
  'tax-preparer': 'Préparateur de déclarations', notary: 'Notaire', 'immigration-consultant': 'Consultant en immigration', 'insurance-broker': 'Courtier d’assurance',
  translator: 'Traducteur', 'legal-document-services': 'Services de documents juridiques',
  // tech & digital
  'computer-repair': 'Réparation d’ordinateurs', 'phone-and-tablet-repair': 'Réparation de téléphones et tablettes', 'home-network-and-wi-fi': 'Réseau domestique et Wi-Fi',
  'smart-home-install': 'Installation domotique', 'tv-mounting': 'Installation de téléviseur', 'web-design': 'Conception Web', 'photography-editing': 'Retouche photo',
  'it-support-for-small-business': 'Soutien informatique pour PME',
  // childcare & family
  babysitter: 'Gardienne', nanny: 'Nounou', 'newborn-care': 'Soins aux nouveau-nés', 'senior-companionship': 'Compagnie pour aînés', 'errand-and-shopping-help': 'Courses et commissions',
};

/** A category or group name in the reader's language (`lang="en"` when French is missing). */
export function categoryName(slug: string, names: Names, locale: Locale): { text: string; lang?: 'en' } {
  if (locale === 'fr' && !names.fr && FR_NAMES[slug]) return { text: FR_NAMES[slug] };
  return localName(names, locale);
}

const CLEANING = new Set(['house-cleaning', 'move-in-move-out-clean', 'carpet-and-upholstery', 'window-cleaning', 'duct-cleaning']);
const BAR = new Set(['cocktail-and-mocktail-bar', 'bartender']);

/**
 * The design shows one category per booking type (mechanic, cleaning, bar, barber, real estate). Its category-specific
 * copy is used for that family; everything else of the type gets the generic wording.
 */
export type Family =
  | 'visit_vehicle' | 'visit' | 'home_cleaning' | 'home' | 'event_bar' | 'event'
  | 'appointment_barber' | 'appointment' | 'consult_realestate' | 'consult';

export function familyOf(kind: ServiceKind, slug: string, vehicle: boolean): Family {
  switch (kind) {
    case 'visit': return vehicle ? 'visit_vehicle' : 'visit';
    case 'home': return CLEANING.has(slug) ? 'home_cleaning' : 'home';
    case 'event': return BAR.has(slug) ? 'event_bar' : 'event';
    case 'appointment': return slug === 'barber-and-hair' ? 'appointment_barber' : 'appointment';
    case 'consult': return slug === 'real-estate-agent' ? 'consult_realestate' : 'consult';
  }
}

/** design 06 `nounBy` (list heading) and the CTA's "See 14 mechanics"; null = the category name / "providers". */
const NOUNS: Partial<Record<Family, { en: [string, string]; fr: [string, string] }>> = {
  visit_vehicle: { en: ['Mobile mechanics', 'mechanics'], fr: ['Mécaniciens mobiles', 'mécaniciens'] },
  home_cleaning: { en: ['Home cleaners', 'cleaners'], fr: ['Préposés au ménage', 'préposés'] },
  event_bar: { en: ['Bartenders & mobile bars', 'bartenders'], fr: ['Barmans et bars mobiles', 'barmans'] },
  appointment_barber: { en: ['Barbers & stylists', 'shops'], fr: ['Barbiers et coiffeurs', 'salons'] },
  consult_realestate: { en: ['Real-estate agents', 'agents'], fr: ['Courtiers immobiliers', 'courtiers'] },
};

export function nouns(family: Family, categoryText: string, locale: Locale): { heading: string; cta: string } {
  const n = NOUNS[family]?.[locale];
  if (n) return { heading: n[0], cta: n[1] };
  return { heading: categoryText, cta: locale === 'fr' ? 'prestataires' : 'providers' };
}

/** The message-key suffix of each piece of per-type copy (messages.ts). */
export function copyKeys(family: Family, kind: ServiceKind) {
  const where = family === 'visit_vehicle' ? 'visit_vehicle' : kind;
  const steps = family === 'visit_vehicle' || family === 'visit' ? family : kind;
  const note = kind === 'home' ? family : kind === 'consult' ? (family === 'consult_realestate' ? 'consult' : 'consult_generic') : kind;
  return {
    kind: `kind_${kind}`, where: `where_${where}`, blurb: `blurb_${family}`, ctaHint: `ctaHint_${family}`, note: `note_${note}`,
    step: (n: 1 | 2 | 3 | 4) => ({ title: `s${n}_${n <= 2 ? steps : kind}`, desc: `s${n}d_${n <= 2 ? steps : kind}` }),
  } as const;
}
