import { defineMessages, messagesFor, type Locale } from '@northline/ui';

/**
 * "Sell or offer a service" — design 06 account › `sell`, copied as designed except the place-specific words (region
 * rule, DECISIONS S-61): the regulators "AMVIC, RECA…" read "for regulated trades" and "AHS Food Handling Permit" is the
 * visitor's province's food-handling permit. French: ours (design/i18n-fr.js has none of these lines).
 */
const SELL = {
  en: {
    title: 'Sell or offer a service · Northline',
    description: 'Offer a service, sell products or run a kitchen on Northline — your customer account carries over.',
    heading: 'Sell or offer a service on Northline',
    lede: 'Your customer account carries over — same login, same passkey. You’ll add business details, pass verification, and get a Studio.',
    providerTitle: 'Offer a service',
    providerBody: 'Mechanic, cleaner, bartender, realtor… appointments, quotes, escrow payouts. Take rate 15% → 9% at Master.',
    providerVerify: 'Verification: KYC · business registry · trade licence for regulated trades · insurance · MFA',
    providerCta: 'Start as a provider',
    sellerTitle: 'Sell products',
    sellerBody: 'Groceries, goods, parts — list products with variants, we pool the delivery. Same take rate, no monthly fee.',
    sellerVerify: 'Verification: KYC · business registry · GST · product-category permits · MFA',
    sellerCta: 'Start as a seller',
    kitchenTitle: 'Run a kitchen',
    kitchenBody: 'Restaurant, caterer, meal prep or home-based food business. Menus, modifiers, combos, live orders, hot delivery.',
    kitchenVerify: 'Verification: {permit} · food-safety certificates · inspection report · allergen attestation · kitchen visit',
    permitIn: '{province} Food Handling Permit',
    permit: 'Food Handling Permit',
    kitchenCta: 'Start as a kitchen',
    loggedInAs: 'Logged in as',
    carryOver: '— your verified phone, passkey and address carry into the business account.',
    startFresh: 'Not a customer yet? Start fresh.',
    guestAsk: 'Already shop on Northline?',
    signIn: 'Sign in',
    guestCarry: 'first — your verified phone, passkey and address carry into the business account.',
    footnote: 'Verification: Stripe KYC, business registry, licence for regulated trades, insurance. Median 1.4 days.',
    chosen: 'Chosen',
    opensStudio: '(opens the Studio)',
  },
  fr: {
    title: 'Vendre ou offrir un service · Northline',
    description: 'Offrez un service, vendez des produits ou gérez une cuisine sur Northline — votre compte client vous suit.',
    heading: 'Vendre ou offrir un service sur Northline',
    lede: 'Votre compte client vous suit — même connexion, même clé d’accès. Vous ajouterez les renseignements de l’entreprise, passerez la vérification et obtiendrez un Studio.',
    providerTitle: 'Offrir un service',
    providerBody: 'Mécanicien, entretien ménager, barman, courtier immobilier… rendez-vous, devis, versements en fiducie. Commission de 15 % → 9 % au niveau Maître.',
    providerVerify: 'Vérification : KYC · registre des entreprises · permis de métier pour les métiers réglementés · assurance · AMF',
    providerCta: 'Commencer comme prestataire',
    sellerTitle: 'Vendre des produits',
    sellerBody: 'Épicerie, produits, pièces — publiez vos produits avec leurs variantes, nous regroupons la livraison. Même commission, aucuns frais mensuels.',
    sellerVerify: 'Vérification : KYC · registre des entreprises · TPS · permis par catégorie de produits · AMF',
    sellerCta: 'Commencer comme vendeur',
    kitchenTitle: 'Gérer une cuisine',
    kitchenBody: 'Restaurant, traiteur, prêt-à-manger ou entreprise alimentaire à domicile. Menus, options, combos, commandes en direct, livraison chaude.',
    kitchenVerify: 'Vérification : {permit} · certificats de salubrité · rapport d’inspection · attestation des allergènes · visite de la cuisine',
    permitIn: 'permis de manipulation des aliments {provinceOf}',
    permit: 'permis de manipulation des aliments',
    kitchenCta: 'Commencer comme cuisine',
    loggedInAs: 'Connecté en tant que',
    carryOver: '— votre téléphone vérifié, votre clé d’accès et votre adresse sont repris dans le compte d’entreprise.',
    startFresh: 'Pas encore client ? Repartez de zéro.',
    guestAsk: 'Vous magasinez déjà sur Northline ?',
    signIn: 'Connectez-vous',
    guestCarry: 'd’abord — votre téléphone vérifié, votre clé d’accès et votre adresse sont repris dans le compte d’entreprise.',
    footnote: 'Vérification : KYC Stripe, registre des entreprises, permis pour les métiers réglementés, assurance. Délai médian : 1,4 jour.',
    chosen: 'Choisi',
    opensStudio: '(ouvre le Studio)',
  },
};

export const useSellT = defineMessages(SELL);
export const sellText = (locale: Locale) => messagesFor(SELL, locale);
