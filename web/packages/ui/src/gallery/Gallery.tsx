import { useState, type ReactNode } from 'react';
import './gallery.css';
import { Bank, ChartBar, ChatCircleText, GearSix, Leaf, MagnifyingGlass, Package, Prohibit, ShoppingCart, SquaresFour, Stack, Storefront, Tag as TagIcon, Warning } from '@phosphor-icons/react';
import { AppShell, WorkspaceSwitcher } from '../AppShell';
import { ActionCard, ActionGrid, ChartCard, KpiStrip, SummaryTable, Thumb } from '../BackOffice';
import { ColumnChart } from '../Charts';
import { Avatar } from '../Layout';
import { Logo } from '../Logo';
import { ShellSearch } from '../ShellSearch';
import { Button } from '../Button';
import { Checkbox, ChoiceTiles, FilterChip, OptionCard, RadioGroup, Segmented, Switch, UnderlineTabs } from '../Choice';
import { DealBadge, MediaFallback, Price, ProductCard, QuantityStepper, ServiceCard, Stars, StatusTag, type StatusTone } from '../Commerce';
import { DataTable, type DataTableColumn } from '../DataTable';
import { FormSection, SaveBar } from '../Editor';
import { listingActions, listingActionsFr, listingColumns, listingColumnsFr, listings, listingsFr, CAN } from '../DataTable/DataTable.fixtures';
import { Pagination, Toast, ToastProvider, useToast } from '../Feedback';
import { Field, Select, TextInput } from '../Field';
import { defineMessages, formatDateRange, formatPrice, formatTimeRange, formatWhen, unitCents, useLocale } from '../i18n';
import { Alert, EmptyState, ErrorState, PageHeader, Skeleton, CardSkeleton } from '../Layout';
import { Dialog, Drawer } from '../Overlay';
import { SearchBar } from '../SearchBar';
import { Tag } from '../Tag';

/**
 * The component gallery (S-168): every component of @northline/ui in the locked E-15 direction, section by section,
 * in the page's language (en / fr-CA). The gallery app (web/apps/gallery) shows it at `/?lang=fr&section=buttons`,
 * Storybook under "Gallery", and the Playwright screenshot baselines (`pnpm test:visual`) photograph each section at
 * 1440 and 390 in both languages. Demo data only: nothing here is a real business.
 */
const useT = defineMessages({
  en: {
    title: 'Component gallery', lede: 'Every @northline/ui component on tokens v2 (A · Plainsight), in English and French.',
    s_tokens: 'Colour roles and type', s_buttons: 'Buttons', s_tabs: 'Tabs', s_choice: 'Segmented control and one-choice tiles',
    s_chips: 'Filter chips', s_checks: 'Checkboxes, radios and option tiles', s_fields: 'Search and fields', s_commerce: 'Price, rating, deal and status',
    s_cards: 'Product and service cards', s_table: 'Data table', s_overlays: 'Dialog, sheet and toast', s_states: 'Empty, error, loading and pagination',
    s_formats: 'Dates and times',
    r_action: 'Action', r_selected: 'Selected', r_commerce: 'Commerce', r_success: 'Success', r_warning: 'Warning', r_danger: 'Danger', r_info: 'Info', r_line: 'Line', r_well: 'Well',
    typeNote: 'IBM Plex Sans 400 · 500 · 600 · 700. Body 14, meta 12, card titles 13.5, page titles 24.',
    addToCart: 'Add to cart', buyNow: 'Buy now', seeDetails: 'See details', suspend: 'Suspend', save: 'Save', cancel: 'Cancel', small: 'Small', large: 'Large', disabled: 'Disabled',
    tabsLabel: 'Orders', toPack: 'To pack', packed: 'Packed', delivered: 'Delivered', issues: 'Issues', all: 'All',
    tip: 'Tip', noTip: 'No tip', time: 'Delivery time', range: 'Period', d7: '7 days', d30: '30 days', d90: '90 days',
    tonight: 'Tonight', vegan: 'Vegan', within: 'Within 2 km', fourUp: '4★ & up', clearAll: 'Clear all', filters: 'Filters',
    delivery: 'Delivery', tonightOpt: 'Tonight, 6–9 p.m.', tomorrowOpt: 'Tomorrow morning', pickup: 'Pickup in store', pooled: 'Pooled with 2 other shops', free: 'Free',
    standard: 'Standard delivery', express: 'Express', expressNote: 'Within 2 hours', instant: 'Instant book', notify: 'Text me when it ships',
    q: 'Search', qPlaceholder: 'Search “sourdough”, “mobile mechanic”…', services: 'Services', shop: 'Shop', food: 'Food',
    name: 'Business name', nameHint: 'What customers see on your page.', email: 'Email', emailError: 'Enter an email address, like name@example.com.',
    department: 'Department', choose: 'Choose…',
    sourdough: 'Country sourdough, 900 g', honey: 'Wildflower honey, 500 g', buns: 'Cinnamon buns, 6-pack', eta: 'Get it <b>tonight, 6–9 p.m.</b>',
    etaTomorrow: 'Get it <b>tomorrow, 8–11 a.m.</b>', mechanic: 'Mobile mechanic', brakes: 'Brake pads, front axle', perHour: '/ hr', place: 'Riverside · 2.4 km',
    statusDelivered: 'Delivered', statusPacked: 'Packed', statusToPack: 'To pack', statusWrong: 'Wrong item', statusPaused: 'Paused',
    vegetarian: 'Vegetarian', newTag: 'New', deal: 'Deal',
    open: 'Open: {what}', dialog: 'Remove this item?', dialogBody: 'It leaves your cart. You can add it again any time.', remove: 'Remove',
    sheet: 'Order NL-48213', sheetBody: 'Two items, packed by 5:30 p.m. for tonight’s run.', toast: 'Saved. Your changes are live.', undo: 'Undo', showToast: 'Show a toast',
    emptyTitle: 'No orders yet', emptyBody: 'Orders show here once customers buy from you.', start: 'Add a product', errorMsg: 'We couldn’t load your orders.',
    loading: 'Loading', pageHeader: 'Orders', pageLede: 'Tonight’s pickup at 5:30 p.m. · 5 orders, 3 still to pack', export: 'Export', notice: 'Payouts go out every Tuesday.',
    s_backoffice: 'Back office: needs you now, figures, sales and a summary table', s_shell: 'Back-office shell',
    needsYou: 'Needs you now', packTitle: '3 orders to pack', packDetail: 'Pack by 5:20 p.m. for tonight’s 5:30 pickup', startPacking: 'Start packing',
    msgTitle: '2 messages waiting', msgDetail: 'Oldest 38 min · K. Nguyen asks about slicing', reply: 'Reply', issueTitle: '1 issue to resolve', issueDetail: 'NL-48188 · the customer reports the wrong item', resolve: 'Resolve',
    payoutTitle: 'Payout on Monday, Oct 26', payoutDetail: '$1,284.60 to RBC ••• 0412', viewPayout: 'View payout',
    kpis: 'Key figures', kSales: 'Sales today', kSalesD: '12% vs last Friday', kOrders: 'Orders', kOrdersD: '5 vs last Friday', kAvg: 'Average order', kAvgD: '$0.40', kRating: 'Rating', kRatingD: '312 ratings · 1 new',
    chart: 'Sales, last 14 days', todaySoFar: 'Today so far · $642.50', previous: 'Previous 13 days · avg $510', top: 'Top products today', product: 'Product', sold: 'Sold', sales: 'Sales', stock: 'Stock', left: '{n} left',
    switchBusiness: 'Switch business', searchStudio: 'Search the Studio', searchPh: 'Search orders, products, customers', ordersMatching: 'Orders matching “{q}”',
    nDashboard: 'Dashboard', gOps: 'Operations', nOrders: 'Orders', nMessages: 'Messages', gCat: 'Catalogue', nProducts: 'Products', nStock: 'Stock', gMoney: 'Money', nPayouts: 'Payouts', nReports: 'Reports', gStore: 'Store', nStorefront: 'Storefront', nSettings: 'Settings', owner: 'Owner',
    fDay: 'A day', fWindow: 'A delivery window', fNight: 'Across midnight', fRange: 'A date range',
    s_worktable: 'Work table and editor: status tabs, quick filters, bulk actions, save bar',
    order: 'order', orders: 'orders', colOrder: 'Order', colPlaced: 'Placed', colCustomer: 'Customer', colItems: 'Items', colWindow: 'Window', colTotal: 'Total', colStatus: 'Status',
    qToday: 'Today', qRun: 'Tonight’s run', markPacked: 'Mark packed', printLabel: 'Print label', searchOrders: 'Search order, customer or item',
    wTonight: 'Tonight 6–9 p.m.', wDelivered: 'Delivered 12:05 p.m.', it1: 'Country sourdough ×2, Seeded rye', it2: 'Sandwich loaf, sliced ×2', it3: 'Seeded rye ×2, Focaccia', it4: 'Seeded rye', it5: 'Sandwich loaf, sliced',
    pricing: 'Price and stock', pricingDesc: 'What customers pay, and how many you have.', price: 'Your price', stockOnHand: 'Stock on hand', saveProduct: 'Save product', draftSaved: 'Draft saved 9:00 a.m.', unsaved: 'Unsaved changes', saveDraft: 'Save draft', submit: 'Submit for review',
  },
  fr: {
    title: 'Galerie des composants', lede: 'Chaque composant de @northline/ui sur les jetons v2 (A · Plainsight), en anglais et en français.',
    s_tokens: 'Rôles des couleurs et typographie', s_buttons: 'Boutons', s_tabs: 'Onglets', s_choice: 'Contrôle segmenté et tuiles à choix unique',
    s_chips: 'Puces de filtre', s_checks: 'Cases à cocher, boutons radio et tuiles d’option', s_fields: 'Recherche et champs', s_commerce: 'Prix, évaluation, rabais et statut',
    s_cards: 'Cartes de produit et de service', s_table: 'Tableau de données', s_overlays: 'Boîte de dialogue, volet et notification', s_states: 'États vide, erreur, chargement et pagination',
    s_formats: 'Dates et heures',
    r_action: 'Action principale', r_selected: 'Sélection', r_commerce: 'Commerce de détail', r_success: 'Succès', r_warning: 'Avertissement', r_danger: 'Risque', r_info: 'Information', r_line: 'Trait', r_well: 'Fond',
    typeNote: 'IBM Plex Sans 400 · 500 · 600 · 700. Texte 14, méta 12, titres de carte 13,5, titres de page 24.',
    addToCart: 'Ajouter au panier', buyNow: 'Acheter', seeDetails: 'Voir les détails', suspend: 'Suspendre', save: 'Enregistrer', cancel: 'Annuler', small: 'Petit', large: 'Grand', disabled: 'Désactivé',
    tabsLabel: 'Commandes', toPack: 'À emballer', packed: 'Emballées', delivered: 'Livrées', issues: 'Problèmes', all: 'Toutes',
    tip: 'Pourboire', noTip: 'Aucun', time: 'Heure de livraison', range: 'Période', d7: '7 jours', d30: '30 jours', d90: '90 jours',
    tonight: 'Ce soir', vegan: 'Végétalien', within: 'À moins de 2 km', fourUp: '4★ et plus', clearAll: 'Tout effacer', filters: 'Filtres',
    delivery: 'Livraison', tonightOpt: 'Ce soir, 18 h – 21 h', tomorrowOpt: 'Demain matin', pickup: 'Cueillette en magasin', pooled: 'Regroupée avec 2 autres boutiques', free: 'Gratuite',
    standard: 'Livraison standard', express: 'Express', expressNote: 'En moins de 2 heures', instant: 'Réservation instantanée', notify: 'M’écrire à l’expédition',
    q: 'Rechercher', qPlaceholder: 'Rechercher « pain au levain », « mécanicien mobile »…', services: 'Services', shop: 'Boutique', food: 'Repas',
    name: 'Nom de l’entreprise', nameHint: 'Ce que la clientèle voit sur votre page.', email: 'Courriel', emailError: 'Entrez une adresse courriel, comme nom@exemple.com.',
    department: 'Rayon', choose: 'Choisir…',
    sourdough: 'Pain de campagne au levain, 900 g', honey: 'Miel de fleurs sauvages, 500 g', buns: 'Brioches à la cannelle, 6', eta: 'Reçu <b>ce soir, 18 h – 21 h</b>',
    etaTomorrow: 'Reçu <b>demain, 8 h – 11 h</b>', mechanic: 'Mécanicien mobile', brakes: 'Plaquettes de frein, essieu avant', perHour: '/ h', place: 'Bord-de-l’Eau · 2,4 km',
    statusDelivered: 'Livrée', statusPacked: 'Emballée', statusToPack: 'À emballer', statusWrong: 'Mauvais article', statusPaused: 'En pause',
    vegetarian: 'Végétarien', newTag: 'Nouveau', deal: 'Aubaine',
    open: 'Ouvrir : {what}', dialog: 'Retirer cet article?', dialogBody: 'Il quitte votre panier. Vous pouvez l’ajouter de nouveau en tout temps.', remove: 'Retirer',
    sheet: 'Commande NL-48213', sheetBody: 'Deux articles, emballés avant 17 h 30 pour la tournée de ce soir.', toast: 'Enregistré. Vos changements sont en ligne.', undo: 'Annuler', showToast: 'Afficher une notification',
    emptyTitle: 'Aucune commande pour l’instant', emptyBody: 'Les commandes s’affichent ici dès que la clientèle achète chez vous.', start: 'Ajouter un produit', errorMsg: 'Impossible de charger vos commandes.',
    loading: 'Chargement', pageHeader: 'Commandes', pageLede: 'Cueillette de ce soir à 17 h 30 · 5 commandes, 3 à emballer', export: 'Exporter', notice: 'Les versements partent chaque mardi.',
    s_backoffice: 'Arrière-boutique : à faire maintenant, chiffres, ventes et tableau résumé', s_shell: 'Cadre de l’arrière-boutique',
    needsYou: 'À faire maintenant', packTitle: '3 commandes à emballer', packDetail: 'Emballer avant 17 h 20 pour la cueillette de 17 h 30', startPacking: 'Commencer l’emballage',
    msgTitle: '2 messages en attente', msgDetail: 'Le plus ancien : 38 min · K. Nguyen demande le tranchage', reply: 'Répondre', issueTitle: '1 problème à régler', issueDetail: 'NL-48188 · mauvais article signalé', resolve: 'Régler',
    payoutTitle: 'Versement le lundi 26 oct.', payoutDetail: '1 284,60 $ vers RBC ••• 0412', viewPayout: 'Voir le versement',
    kpis: 'Chiffres clés', kSales: 'Ventes aujourd’hui', kSalesD: '12 % par rapport à vendredi dernier', kOrders: 'Commandes', kOrdersD: '5 par rapport à vendredi dernier', kAvg: 'Commande moyenne', kAvgD: '0,40 $', kRating: 'Note', kRatingD: '312 notes · 1 nouvelle',
    chart: 'Ventes, 14 derniers jours', todaySoFar: 'Aujourd’hui jusqu’ici · 642,50 $', previous: '13 jours précédents · moy. 510 $', top: 'Meilleurs produits aujourd’hui', product: 'Produit', sold: 'Vendus', sales: 'Ventes', stock: 'Stock', left: '{n} restants',
    switchBusiness: 'Changer d’entreprise', searchStudio: 'Rechercher dans le Studio', searchPh: 'Rechercher commandes, produits, clients', ordersMatching: 'Commandes correspondant à « {q} »',
    nDashboard: 'Tableau de bord', gOps: 'Opérations', nOrders: 'Commandes', nMessages: 'Messages', gCat: 'Catalogue', nProducts: 'Produits', nStock: 'Stock', gMoney: 'Argent', nPayouts: 'Versements', nReports: 'Rapports', gStore: 'Boutique', nStorefront: 'Vitrine', nSettings: 'Paramètres', owner: 'Propriétaire',
    fDay: 'Un jour', fWindow: 'Une plage de livraison', fNight: 'Après minuit', fRange: 'Une période',
    s_worktable: 'Tableau de travail et éditeur : onglets de statut, filtres rapides, actions groupées, barre d’enregistrement',
    order: 'commande', orders: 'commandes', colOrder: 'Commande', colPlaced: 'Passée', colCustomer: 'Client', colItems: 'Articles', colWindow: 'Plage', colTotal: 'Total', colStatus: 'Statut',
    qToday: 'Aujourd’hui', qRun: 'Tournée de ce soir', markPacked: 'Marquer emballée', printLabel: 'Imprimer l’étiquette', searchOrders: 'Rechercher commande, client ou article',
    wTonight: 'Ce soir 18 h – 21 h', wDelivered: 'Livrée à 12 h 05', it1: 'Pain de campagne ×2, Seigle aux graines', it2: 'Pain de mie tranché ×2', it3: 'Seigle aux graines ×2, Focaccia', it4: 'Seigle aux graines', it5: 'Pain de mie tranché',
    pricing: 'Prix et stock', pricingDesc: 'Ce que la clientèle paie, et combien vous en avez.', price: 'Votre prix', stockOnHand: 'Stock disponible', saveProduct: 'Enregistrer le produit', draftSaved: 'Brouillon enregistré à 9 h', unsaved: 'Changements non enregistrés', saveDraft: 'Enregistrer le brouillon', submit: 'Soumettre pour vérification',
  },
});
type GalleryT = ReturnType<typeof useT>;

/** Demo names: data, the same in both languages. */
const SHOP = 'Loaf & Ladder Bakery · Maple Row';
const FONT = 'IBM Plex Sans';
const HIVE = 'Hive & Hollow Apiary';

export interface GallerySection {
  id: string; title: (t: GalleryT) => string; render: (t: GalleryT, photo?: string) => ReactNode;
  /** Shown only on its own (`?section=<id>`): a whole page shell brings its own landmarks (banner, main). */
  solo?: boolean;
}

const Row = ({ children, label }: { children: ReactNode; label?: string }) => (
  <div className="nl-gallery-row">{label ? <span className="nl-gallery-label">{label}</span> : null}<div className="nl-gallery-items">{children}</div></div>
);

function Tokens({ t }: { t: GalleryT }) {
  const roles: [string, string][] = [['action-primary', t('r_action')], ['selected', t('r_selected')], ['commerce', t('r_commerce')], ['success', t('r_success')], ['warning', t('r_warning')], ['danger', t('r_danger')], ['info', t('r_info')], ['border-strong', t('r_line')], ['well', t('r_well')]];
  return (
    <>
      <ul className="nl-gallery-swatches">
        {roles.map(([role, label]) => <li key={role}><span className="nl-gallery-swatch" data-role={role} /><span><b>{label}</b><code>{`--color-${role}`}</code></span></li>)}
      </ul>
      <p className="nl-gallery-type">{FONT}</p>
      <p className="nl-muted">{t('typeNote')}</p>
    </>
  );
}

function Tabs({ t }: { t: GalleryT }) {
  const [v, setV] = useState('toPack');
  return <UnderlineTabs aria-label={t('tabsLabel')} value={v} onChange={setV} options={[{ value: 'all', label: t('all'), count: 38 }, { value: 'toPack', label: t('toPack'), count: 3 }, { value: 'packed', label: t('packed'), count: 2 }, { value: 'delivered', label: t('delivered'), count: 32 }, { value: 'issues', label: t('issues'), count: 1, tone: 'danger' }]} />;
}

function Choices({ t }: { t: GalleryT }) {
  const { locale } = useLocale();
  const [tip, setTip] = useState<'0' | '2' | '3' | '5'>('3');
  const [slot, setSlot] = useState<string | null>('0930');
  const [range, setRange] = useState<'7' | '30' | '90'>('30');
  const money = (d: number) => (locale === 'fr' ? `${d} $` : `$${d}`);
  return (
    <>
      <Row label={t('tip')}><Segmented name="gallery-tip" aria-label={t('tip')} value={tip} onChange={setTip} options={[{ value: '0', label: t('noTip') }, { value: '2', label: money(2) }, { value: '3', label: money(3) }, { value: '5', label: money(5) }]} /></Row>
      <Row label={t('range')}><Segmented name="gallery-range" aria-label={t('range')} value={range} onChange={setRange} options={[{ value: '7', label: t('d7') }, { value: '30', label: t('d30') }, { value: '90', label: t('d90') }]} /></Row>
      <Row label={t('time')}><ChoiceTiles aria-label={t('time')} value={slot} onChange={setSlot} options={[{ value: '0930', label: locale === 'fr' ? '9 h 30' : '9:30 a.m.' }, { value: '1100', label: locale === 'fr' ? '11 h' : '11:00 a.m.' }, { value: '1300', label: locale === 'fr' ? '13 h' : '1:00 p.m.', disabled: true }]} /></Row>
    </>
  );
}

function Chips({ t }: { t: GalleryT }) {
  const [on, setOn] = useState<Record<string, boolean>>({ tonight: true, fourUp: true });
  const toggle = (k: string) => setOn(s => ({ ...s, [k]: !s[k] }));
  const keys = ['tonight', 'fourUp', 'vegan', 'within'] as const;
  return (
    <>
      <Row label={t('filters')}>{keys.map(k => <FilterChip key={k} label={t(k)} applied={!!on[k]} onToggle={() => toggle(k)} />)}</Row>
      <Row>{keys.filter(k => on[k]).map(k => <FilterChip key={k} label={t(k)} applied onRemove={() => toggle(k)} />)}<button type="button" className="nl-clear" onClick={() => setOn({})}>{t('clearAll')}</button></Row>
    </>
  );
}

function Checks({ t }: { t: GalleryT }) {
  const [a, setA] = useState(true), [b, setB] = useState(false), [sw, setSw] = useState(true), [opt, setOpt] = useState('standard');
  return (
    <div className="nl-gallery-cols">
      <div className="nl-gallery-stack">
        <Checkbox checked={a} onChange={setA} label={t('tonightOpt')} count={64} />
        <Checkbox checked={b} onChange={setB} label={t('tomorrowOpt')} count={86} />
        <Checkbox checked={false} indeterminate onChange={() => {}} label={t('pickup')} count={52} />
        <label className="nl-gallery-switch"><Switch checked={sw} onChange={setSw} label={t('notify')} />{t('notify')}</label>
      </div>
      <RadioGroup className="nl-gallery-stack" aria-label={t('delivery')}>
        {(['standard', 'express'] as const).map(k => (
          <OptionCard key={k} role="radio" mark="radio" selected={opt === k} onClick={() => setOpt(k)} title={t(k)} description={k === 'standard' ? t('pooled') : t('expressNote')} trailing={<b>{k === 'standard' ? t('free') : <Price cents={599} size="sm" />}</b>} />
        ))}
      </RadioGroup>
    </div>
  );
}

function Fields({ t }: { t: GalleryT }) {
  const [q, setQ] = useState('');
  const [scope, setScope] = useState('shop');
  return (
    <div className="nl-gallery-stack">
      <SearchBar variant="header" value={q} onChange={setQ} onSubmit={() => {}} label={t('q')} placeholder={t('qPlaceholder')}
        scope={{ value: scope, onChange: setScope, options: [{ value: 'services', label: t('services') }, { value: 'shop', label: t('shop') }, { value: 'food', label: t('food') }] }} />
      <div className="nl-grid">
        <Field label={t('name')} hint={t('nameHint')}><TextInput defaultValue="Loaf & Ladder" /></Field>
        <Field label={t('email')} error={t('emailError')}><TextInput type="email" defaultValue="loaf@" /></Field>
        <Field label={t('department')}><Select placeholder={t('choose')} options={[{ value: 'services', label: t('services') }, { value: 'shop', label: t('shop') }, { value: 'food', label: t('food') }]} /></Field>
        <Field label={t('disabled')}><TextInput disabled defaultValue="—" /></Field>
      </div>
    </div>
  );
}

function Commerce({ t }: { t: GalleryT }) {
  return (
    <>
      <Row><Price cents={850} size="xl" unit={{ cents: unitCents(850, 900, '100g'), per: '100g' }} /><Stars rating={4.8} reviewCount={312} /><DealBadge percent={12} /><Price cents={1400} compareAtCents={1600} unit={{ cents: 233, per: 'each' }} /></Row>
      <Row><StatusTag tone="success">{t('statusDelivered')}</StatusTag><StatusTag tone="info">{t('statusPacked')}</StatusTag><StatusTag tone="warning">{t('statusToPack')}</StatusTag><StatusTag tone="danger">{t('statusWrong')}</StatusTag><StatusTag>{t('statusPaused')}</StatusTag></Row>
      <Row><Tag tone="neutral">{t('vegetarian')}</Tag><Tag tone="brand">{t('newTag')}</Tag><Tag tone="commerce">{t('deal')}</Tag><QuantityStepper value={1} max={9} onChange={() => {}} name={t('sourdough')} inCart /></Row>
    </>
  );
}

const Eta = ({ html }: { html: string }) => {
  const [a, b] = html.split(/<b>|<\/b>/);
  return <>{a}<b>{b}</b></>;
};

function Cards({ t, photo }: { t: GalleryT; photo?: string }) {
  const [qty, setQty] = useState(0);
  return (
    <>
      <ul className="nl-card-grid nl-gallery-cards">
        <li><ProductCard href="#sourdough" name={t('sourdough')} imageUrl={photo} meta={SHOP} rating={4.8} reviewCount={312} priceCents={850} unit={{ cents: 94, per: '100g' }} eta={<Eta html={t('eta')} />} inCart={qty} onAdd={() => setQty(1)} onQuantity={setQty} /></li>
        <li><ProductCard href="#honey" name={t('honey')} fallback={<MediaFallback name={HIVE} icon={Leaf} caption={HIVE} />} meta={HIVE} rating={4.9} reviewCount={41} priceCents={1350} unit={{ cents: 270, per: '100g' }} eta={<Eta html={t('etaTomorrow')} />} lowStock={4} onAdd={() => {}} /></li>
        <li><ProductCard href="#buns" name={t('buns')} imageUrl={photo} meta={SHOP} rating={4.8} reviewCount={221} priceCents={1400} compareAtCents={1600} unit={{ cents: 233, per: 'each' }} onAdd={() => {}} /></li>
        <li><ProductCard href="#gone" name={t('sourdough')} meta={SHOP} priceCents={850} soldOut fallback={<MediaFallback name={SHOP} icon={Package} />} /></li>
      </ul>
      <div className="nl-gallery-cols">
        <ServiceCard href="#pw" kind={t('mechanic')} name="Prairie Wrench" rating={4.9} reviewCount={312} priceCents={8900} priceSuffix={t('perHour')} place={t('place')} verified />
        <ServiceCard onSelect={() => {}} selected name={t('brakes')} priceCents={18900} fromPrice={false} mark={<MediaFallback name="PW" icon={Storefront} />} />
      </div>
    </>
  );
}

function Table() {
  const { locale } = useLocale();
  return locale === 'fr'
    ? <DataTable entity="annonce" plural="annonces" roleName="Propriétaire" can={CAN.record('owner')} columns={listingColumnsFr} rows={listingsFr.slice(0, 6)} rowTones={r => ({ vet: r.vetTone })} actions={listingActionsFr} pageSize={6} searchPlaceholder="Rechercher par nom ou UGS…" />
    : <DataTable entity="listing" roleName="Owner" can={CAN.record('owner')} columns={listingColumns} rows={listings.slice(0, 6)} rowTones={r => ({ vet: r.vetTone })} actions={listingActions} pageSize={6} searchPlaceholder="Search by name or SKU…" />;
}

function Overlays({ t, open: initial }: { t: GalleryT; open?: string | null }) {
  const [open, setOpen] = useState<string | null>(initial ?? null);
  const toast = useToast();
  return (
    <>
      <Row>
        <Button variant="secondary" onClick={() => setOpen('dialog')}>{t('open', { what: t('dialog') })}</Button>
        <Button variant="secondary" onClick={() => setOpen('sheet')}>{t('open', { what: t('sheet') })}</Button>
        <Button variant="secondary" onClick={() => toast.show({ tone: 'success', children: t('toast'), action: <button type="button" className="btn btn-tertiary btn-sm">{t('undo')}</button> })}>{t('showToast')}</Button>
      </Row>
      <Row><Toast tone="success" action={<button type="button" className="btn btn-tertiary btn-sm">{t('undo')}</button>} onClose={() => {}}>{t('toast')}</Toast></Row>
      <Dialog open={open === 'dialog'} onClose={() => setOpen(null)} title={t('dialog')} dismissible sheetOnPhone
        actions={<><Button variant="secondary" onClick={() => setOpen(null)}>{t('cancel')}</Button><Button variant="destructive" onClick={() => setOpen(null)}>{t('remove')}</Button></>}>
        <p className="nl-muted">{t('dialogBody')}</p>
      </Dialog>
      <Drawer open={open === 'sheet'} onClose={() => setOpen(null)} title={t('sheet')} footer={<Button onClick={() => setOpen(null)}>{t('save')}</Button>}>
        <p>{t('sheetBody')}</p><StatusTag tone="warning">{t('statusToPack')}</StatusTag>
      </Drawer>
    </>
  );
}

function States({ t }: { t: GalleryT }) {
  const [page, setPage] = useState(2);
  return (
    <div className="nl-gallery-stack">
      <PageHeader title={t('pageHeader')} lede={t('pageLede')} actions={<Button variant="secondary">{t('export')}</Button>} />
      <Alert tone="info">{t('notice')}</Alert>
      <EmptyState icon={ShoppingCart} title={t('emptyTitle')} action={<Button>{t('start')}</Button>}>{t('emptyBody')}</EmptyState>
      <ErrorState message={t('errorMsg')} onRetry={() => {}} />
      <div className="nl-gallery-cards-skel" role="group" aria-label={t('loading')}>{[0, 1, 2, 3].map(i => <CardSkeleton key={i} />)}</div>
      <Skeleton className="nl-skel-text" width="60%" />
      <Pagination page={page} pages={4} onChange={setPage} range={{ from: 25, to: 48, total: 86 }} />
    </div>
  );
}

function Formats({ t }: { t: GalleryT }) {
  const { locale } = useLocale();
  const z = 'UTC';
  const rows: [string, string][] = [
    [t('fDay'), formatWhen('2026-10-19', locale, 'long')],
    [t('fWindow'), formatTimeRange('2026-10-19T18:00:00Z', '2026-10-19T21:00:00Z', locale, z)],
    [t('fNight'), formatTimeRange('2026-10-19T21:36:00Z', '2026-10-20T00:36:00Z', locale, z)],
    [t('fRange'), formatDateRange('2026-10-30', '2026-11-02', locale)],
  ];
  return <dl className="bo-facts">{rows.map(([k, v]) => <div key={k} className="bo-fact"><dt>{k}</dt><dd>{v}</dd></div>)}</dl>;
}

const SALES = [410, 380, 450, 495, 690, 740, 355, 425, 400, 470, 515, 700, 810, 642.5];
const DAYS = { en: ['S', 'S', 'M', 'T', 'W', 'T', 'F', 'S', 'S', 'M', 'T', 'W', 'T', 'F'], fr: ['S', 'D', 'L', 'M', 'M', 'J', 'V', 'S', 'D', 'L', 'M', 'M', 'J', 'V'] };
interface TopRow { id: string; name: string; sold: number; cents: number; stock: number; photo?: string }

function BackOfficeDemo({ t, photo }: { t: GalleryT; photo?: string }) {
  const { locale } = useLocale();
  const money = (c: number) => (locale === 'fr' ? `${(c / 100).toFixed(2).replace('.', ',')} $` : `$${(c / 100).toFixed(2)}`);
  const rows: TopRow[] = [{ id: 'a', name: t('sourdough'), sold: 22, cents: 18700, stock: 14, photo }, { id: 'b', name: t('honey'), sold: 11, cents: 15400, stock: 4 }, { id: 'c', name: t('buns'), sold: 9, cents: 9900, stock: 21 }];
  return (
    <div className="nl-gallery-stack">
      <ActionGrid title={t('needsYou')} count={4}>
        <ActionCard tone="danger" icon={Warning} title={t('issueTitle')} detail={t('issueDetail')} action={{ label: t('resolve'), onClick: () => {} }} primary />
        <ActionCard tone="warning" icon={Package} title={t('packTitle')} detail={t('packDetail')} action={{ label: t('startPacking'), onClick: () => {} }} />
        <ActionCard tone="info" icon={ChatCircleText} title={t('msgTitle')} detail={t('msgDetail')} action={{ label: t('reply'), onClick: () => {} }} />
        <ActionCard tone="success" icon={Bank} title={t('payoutTitle')} detail={t('payoutDetail')} action={{ label: t('viewPayout'), onClick: () => {} }} />
      </ActionGrid>
      <KpiStrip label={t('kpis')} items={[
        { key: 's', label: t('kSales'), value: money(64250), delta: t('kSalesD'), trend: 'up', arrow: true },
        { key: 'o', label: t('kOrders'), value: '38', delta: t('kOrdersD'), trend: 'up', arrow: true },
        { key: 'a', label: t('kAvg'), value: money(1691), delta: t('kAvgD'), trend: 'down', arrow: true },
        { key: 'r', label: t('kRating'), value: locale === 'fr' ? '4,8' : '4.8', delta: t('kRatingD') },
      ]} />
      <div className="bo-cols">
        <ChartCard id="gallery-chart" title={t('chart')} actions={<Segmented name="gallery-sales-range" aria-label={t('range')} value="14" onChange={() => {}} options={[{ value: '14', label: '14' }, { value: '30', label: '30' }]} />}>
          <ColumnChart title={t('chart')} data={SALES.map((v, i) => ({ label: DAYS[locale][i]!, value: Math.round(v * 100) }))} highlightLast legend={{ current: t('todaySoFar'), past: t('previous') }} format={money} axisFormat={c => money(c).replace(/[.,]00/, '')} />
        </ChartCard>
        <ChartCard id="gallery-top" title={t('top')}>
          <SummaryTable<TopRow> caption={t('top')} rows={rows} rowKey={r => r.id} columns={[
            { key: 'n', label: t('product'), render: r => <span className="bo-item"><Thumb src={r.photo} name={r.name} /><span>{r.name}</span></span> },
            { key: 's', label: t('sold'), align: 'end', render: r => r.sold },
            { key: 'c', label: t('sales'), align: 'end', hideOnPhone: true, render: r => money(r.cents) },
            { key: 'k', label: t('stock'), align: 'end', render: r => <StatusTag tone={r.stock < 5 ? 'warning' : 'neutral'}>{r.stock < 5 ? t('left', { n: r.stock }) : r.stock}</StatusTag> },
          ]} />
        </ChartCard>
      </div>
    </div>
  );
}

interface WorkRow { id: string; ref: string; placed: string; who: string; items: string; window: string; total: number; state: string; code: 'to_pack' | 'packed' | 'delivered' | 'issue' }

function WorkTable({ t }: { t: GalleryT }) {
  const { locale } = useLocale();
  const s = (code: WorkRow['code']) => (code === 'to_pack' ? t('statusToPack') : code === 'packed' ? t('statusPacked') : code === 'issue' ? t('statusWrong') : t('statusDelivered'));
  const time = (h: number, m: number) => (locale === 'fr' ? `${h} h ${String(m).padStart(2, '0')}` : `${h > 12 ? h - 12 : h}:${String(m).padStart(2, '0')} ${h >= 12 ? 'p.m.' : 'a.m.'}`);
  const rows: WorkRow[] = [
    { id: '1', ref: 'NL-48213', placed: time(15, 42), who: 'A. Osei', items: t('it1'), window: t('wTonight'), total: 2625, code: 'to_pack', state: '' },
    { id: '2', ref: 'NL-48219', placed: time(15, 55), who: 'K. Nguyen', items: t('it2'), window: t('wTonight'), total: 1550, code: 'to_pack', state: '' },
    { id: '3', ref: 'NL-48201', placed: time(14, 10), who: 'M. Tran', items: t('it3'), window: t('wTonight'), total: 2500, code: 'packed', state: '' },
    { id: '4', ref: 'NL-48188', placed: time(11, 20), who: 'D. Kowalski', items: t('it4'), window: t('wDelivered'), total: 925, code: 'issue', state: '' },
    { id: '5', ref: 'NL-48177', placed: time(11, 5), who: 'P. Singh', items: t('it5'), window: t('wDelivered'), total: 775, code: 'delivered', state: '' },
  ].map(r => ({ ...r, state: s(r.code as WorkRow['code']) })) as WorkRow[];
  const tone = (r: WorkRow): StatusTone => (r.code === 'to_pack' ? 'warning' : r.code === 'packed' ? 'info' : r.code === 'issue' ? 'danger' : 'success');
  const columns: DataTableColumn<WorkRow>[] = [
    { key: 'ref', label: t('colOrder'), primary: true, nowrap: true },
    { key: 'placed', label: t('colPlaced'), nowrap: true },
    { key: 'who', label: t('colCustomer'), card: 'line' },
    { key: 'items', label: t('colItems'), card: 'line' },
    { key: 'window', label: t('colWindow'), card: 'meta' },
    { key: 'total', label: t('colTotal'), type: 'money', format: v => formatPrice(v as number, locale), card: 'end' },
    { key: 'state', label: t('colStatus'), type: 'tag', status: tone },
  ];
  return (
    <div className="nl-gallery-stack">
      <DataTable<WorkRow> entity={t('order')} plural={t('orders')} columns={columns} rows={rows} searchPlaceholder={t('searchOrders')} can={{ update: true, export: false }}
        views={[{ id: 'all', label: t('all') }, { id: 'pack', label: t('toPack'), when: r => r.code === 'to_pack' }, { id: 'packed', label: t('packed'), when: r => r.code === 'packed' }, { id: 'delivered', label: t('delivered'), when: r => r.code === 'delivered' }, { id: 'issues', label: t('issues'), when: r => r.code === 'issue', tone: 'danger' }]}
        quickFilters={[{ id: 'today', label: t('qToday'), when: () => true }, { id: 'run', label: t('qRun'), when: r => r.window === t('wTonight') }]} initialQuickFilters={['today']}
        actions={[{ id: 'pack', label: t('markPacked'), inline: true, emphasis: 'primary', when: { key: 'code', in: ['to_pack'] } }, { id: 'label', label: t('printLabel'), inline: true, bulk: false, emphasis: 'secondary', when: { key: 'code', in: ['packed'] } }]}
        onAction={() => false} onOpen={() => {}} />
      <FormSection title={t('pricing')} description={t('pricingDesc')}>
        <div className="nl-gallery-pair">
          <Field label={t('price')}><TextInput defaultValue={formatPrice(850, locale)} /></Field>
          <Field label={t('stockOnHand')}><TextInput defaultValue="14" inputMode="numeric" /></Field>
        </div>
      </FormSection>
      <SaveBar sticky={false} label={t('saveProduct')} status={<StatusTag>{t('draftSaved')}</StatusTag>} note={t('unsaved')}>
        <Button variant="secondary">{t('saveDraft')}</Button>
        <Button>{t('submit')}</Button>
      </SaveBar>
    </div>
  );
}

/** The demo persona of the Studio's own tests and shots (a person's name: not translated). */
const PERSONA = ['Ravi', 'Sandhu'].join(' ');

function ShellDemo({ t }: { t: GalleryT }) {
  const [cur, setCur] = useState('dashboard');
  const item = (key: string, label: string, icon: typeof Bank, badge?: string) => ({ key, label, icon, badge, href: `#${key}` });
  return (
    <div className="nl-gallery-shell">
      <AppShell brand={<Logo size={28} product="Studio" />}
        headerStart={<WorkspaceSwitcher label={t('switchBusiness')} name="Loaf & Ladder Bakery" meta="Riverside" items={[{ label: 'Loaf & Ladder Bakery', checked: true, onSelect: () => {} }]} />}
        search={<ShellSearch label={t('searchStudio')} placeholder={t('searchPh')} scopes={[{ key: 'o', label: q => t('ordersMatching', { q }) }]} onSearch={() => {}} />}
        headerEnd={<button type="button" className="nl-topbar-item"><Avatar initials="RS" size={30} /><span className="nl-topbar-item-text">{PERSONA}<span>{t('owner')}</span></span></button>}
        pinned={[item('dashboard', t('nDashboard'), SquaresFour)]}
        groups={[
          { label: t('gOps'), icon: Package, items: [item('orders', t('nOrders'), Package, '3'), item('messages', t('nMessages'), ChatCircleText, '2')] },
          { label: t('gCat'), icon: TagIcon, items: [item('products', t('nProducts'), TagIcon), item('stock', t('nStock'), Stack, '1')] },
          { label: t('gMoney'), icon: Bank, items: [item('payouts', t('nPayouts'), Bank), item('reports', t('nReports'), ChartBar)] },
          { label: t('gStore'), icon: Storefront, items: [item('storefront', t('nStorefront'), Storefront), item('settings', t('nSettings'), GearSix)] },
        ]}
        currentKey={cur} onNavigate={i => setCur(i.key)}>
        <PageHeader title={t('nDashboard')} lede={t('pageLede')} />
      </AppShell>
    </div>
  );
}

export const GALLERY_SECTIONS: readonly GallerySection[] = [
  { id: 'tokens', title: t => t('s_tokens'), render: t => <Tokens t={t} /> },
  {
    id: 'buttons', title: t => t('s_buttons'), render: t => (
      <>
        <Row><Button><ShoppingCart size={17} aria-hidden />{t('addToCart')}</Button><Button variant="secondary">{t('buyNow')}</Button><Button variant="tertiary">{t('seeDetails')}</Button><Button variant="destructive"><Prohibit size={17} aria-hidden />{t('suspend')}</Button></Row>
        <Row><Button size="sm">{t('small')}</Button><Button size="lg">{t('large')}</Button><Button variant="dark"><MagnifyingGlass size={17} aria-hidden />{t('q')}</Button><Button disabled>{t('disabled')}</Button></Row>
      </>
    ),
  },
  { id: 'tabs', title: t => t('s_tabs'), render: t => <Tabs t={t} /> },
  { id: 'choice', title: t => t('s_choice'), render: t => <Choices t={t} /> },
  { id: 'chips', title: t => t('s_chips'), render: t => <Chips t={t} /> },
  { id: 'checks', title: t => t('s_checks'), render: t => <Checks t={t} /> },
  { id: 'fields', title: t => t('s_fields'), render: t => <Fields t={t} /> },
  { id: 'commerce', title: t => t('s_commerce'), render: t => <Commerce t={t} /> },
  { id: 'cards', title: t => t('s_cards'), render: (t, photo) => <Cards t={t} photo={photo} /> },
  { id: 'table', title: t => t('s_table'), render: () => <Table /> },
  { id: 'overlays', title: t => t('s_overlays'), render: t => <Overlays t={t} /> },
  { id: 'states', title: t => t('s_states'), render: t => <States t={t} /> },
  { id: 'formats', title: t => t('s_formats'), render: t => <Formats t={t} /> },
  { id: 'backoffice', title: t => t('s_backoffice'), render: (t, photo) => <BackOfficeDemo t={t} photo={photo} /> },
  { id: 'worktable', title: t => t('s_worktable'), render: t => <WorkTable t={t} /> },
  { id: 'shell', title: t => t('s_shell'), render: t => <ShellDemo t={t} />, solo: true },
];

/**
 * The gallery page: every section (or only `section`), each a `<section data-gallery="<id>">` the screenshot tests
 * photograph. `open` starts the overlays section with its dialog or sheet open; `photo` is a product photo's URL for
 * the cards (the app serves one; without it the cards show their fallback).
 */
export function Gallery({ section, open, photo }: { section?: string | null; open?: string | null; photo?: string }) {
  const t = useT();
  const shown = section ? GALLERY_SECTIONS.filter(s => s.id === section) : GALLERY_SECTIONS.filter(s => !s.solo);
  const solo = shown.length === 1 && !!shown[0]!.solo;
  return (
    <ToastProvider>
      <div className="nl-gallery">
        {solo ? null : <header className="nl-gallery-head"><h1>{t('title')}</h1><p className="nl-muted">{t('lede')}</p></header>}
        {shown.map(s => (s.solo
          // a page shell is the page: no region or banner around its own banner and main
          ? <div key={s.id} className="nl-gallery-section" data-gallery={s.id}><p className="nl-gallery-solo-title" id={`gallery-${s.id}`}>{s.title(t)}</p>{s.render(t, photo)}</div>
          : (
            <section key={s.id} className="nl-gallery-section" data-gallery={s.id} aria-labelledby={`gallery-${s.id}`}>
              <h2 id={`gallery-${s.id}`}>{s.title(t)}</h2>
              {s.id === 'overlays' ? <Overlays t={t} open={open} /> : s.render(t, photo)}
            </section>
          )))}
      </div>
    </ToastProvider>
  );
}

/** One section by id, for Storybook. */
export function GallerySectionView({ id }: { id: string }) {
  return <Gallery section={id} />;
}
