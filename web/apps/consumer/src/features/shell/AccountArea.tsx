import { Bell, CalendarCheck, ChatCircleText, ClipboardText, CreditCard, Heart, Leaf, Lifebuoy, MapPinLine, Receipt, ShieldCheck, SignOut, Star, Storefront, Translate, User, UserCircle, Wallet } from '@phosphor-icons/react';
import { AccountMenu, formatMoney, formatNumber, fullNavEnabled, useLocale, type AccountMenuSection } from '@northline/ui';
import { useAccountSummary, type AccountSummary } from '../account/api';
import type { SessionUser } from '../session/api';
import { useShellT } from './messages';

/** Where each menu item goes (routes of S-58 / S-59 / S-61; docs/CONSUMER_WEB_PLAN.md § Routes). */
export const ACCOUNT_LINKS = {
  orders: '/account/orders',
  bookings: '/account/orders?tab=bookings',
  quoteRequests: '/account/orders?tab=quotes',
  messages: '/account/messages',
  account: '/account',
  favourites: '/account?tab=favourites',
  wallet: '/account?tab=wallet',
  profile: '/account?tab=profile',
  addresses: '/account?tab=addresses',
  payments: '/account?tab=payments',
  security: '/account?tab=security',
  notifications: '/account?tab=notifications',
  language: '/account?tab=language',
  dietary: '/account?tab=dietary',
  plus: '/account?tab=plus',
  help: '/account?tab=help',
  sell: '/sell',
} as const;

/** Signed in: the design's account menu. Values come from the account summary when the api has it. */
export function AccountArea({ user, onSignOut, onToggleLocale }: { user: SessionUser; onSignOut: () => void; onToggleLocale: () => void }) {
  const t = useShellT();
  const { locale } = useLocale();
  const { data: s } = useAccountSummary(true);
  const name = `${user.firstName} ${user.lastName}`.trim() || user.email || '';
  const v = values(s ?? null, t, locale, name);
  const item = (key: keyof typeof ACCOUNT_LINKS, icon: typeof Receipt, value?: string) => ({ key, label: t(key === 'sell' ? 'sellOrOffer' : key === 'account' ? 'yourAccount' : key), icon, value, href: ACCOUNT_LINKS[key] });
  // redesign § 3.6: Wallet & points, Dietary and Plus are hidden for the demo (VITE_NL_FULL_NAV brings them back)
  const full = fullNavEnabled();
  const sections: AccountMenuSection[] = [
    { heading: t('activity'), items: [item('orders', Receipt, v.orders), item('bookings', CalendarCheck), item('quoteRequests', ClipboardText), item('messages', ChatCircleText), item('favourites', Heart, v.favourites), ...(full ? [item('wallet', Wallet, v.wallet)] : [])] },
    { heading: t('account'), items: [item('account', UserCircle), item('profile', User, name), item('addresses', MapPinLine, v.addresses), item('payments', CreditCard, v.payments), item('security', ShieldCheck, v.security)] },
    // redesign § 3.3: the FR/EN switch lives here and in the footer (it left the header)
    { heading: t('preferences'), items: [item('notifications', Bell, v.quiet), { key: 'language', label: t('langSwitch'), lang: locale === 'fr' ? 'en-CA' : 'fr-CA', icon: Translate, value: v.language, onSelect: onToggleLocale }, ...(full ? [item('dietary', Leaf, v.dietary), item('plus', Star, v.plus)] : [])] },
    { heading: '', items: [item('help', Lifebuoy, v.help), item('sell', Storefront), { key: 'signOut', label: t('signOut'), icon: SignOut, onSelect: onSignOut }] },
  ];
  const email = user.email ?? '';
  const detail = s?.reliability != null && email ? t('reliability', { email, score: formatNumber(s.reliability, locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }) : email || undefined;
  const card = full && s?.points ? (
    <>
      <span><strong>{t('points', { count: formatNumber(s.points.balance, locale) })}</strong> · {formatMoney(s.points.valueCents, locale)}</span>
      {s.plus != null && <span className="tag tag-highlight">{t(s.plus ? 'plusTag' : 'standardTag')}</span>}
    </>
  ) : undefined;
  return <AccountMenu user={{ name, initials: user.initials, detail }} headerAction={{ label: t('addPhoto'), href: ACCOUNT_LINKS.profile }} card={card} sections={sections} />;
}

type T = ReturnType<typeof useShellT>;
/** The values next to the items (design 06 `menuItems`); absent fields show nothing. */
export function values(s: AccountSummary | null, t: T, locale: 'en' | 'fr', name: string) {
  const n = (x: number) => formatNumber(x, locale);
  return {
    name,
    orders: s?.activeOrders ? t('active', { count: n(s.activeOrders) }) : undefined,
    favourites: s?.favourites != null ? n(s.favourites) : undefined,
    wallet: s?.points ? t('points', { count: n(s.points.balance) }) : undefined,
    addresses: s?.addresses ? t('members', { count: n(s.addresses.count), members: n(s.addresses.members) }) : undefined,
    payments: s?.paymentMethod ? `${s.paymentMethod.brand} ··${s.paymentMethod.last4}` : undefined,
    security: s?.signIn ? t(s.signIn === 'passkey' ? 'passkey' : s.signIn === 'totp' ? 'authenticator' : 'sms') : undefined,
    quiet: s?.quietHours ? t('quiet', { from: s.quietHours.from, to: s.quietHours.to }) : undefined,
    language: s?.province ? t('languageValue', { province: s.province }) : undefined,
    dietary: s?.dietary?.length ? s.dietary.join(', ') : undefined,
    plus: s?.plus != null ? t(s.plus ? 'plusActive' : 'plusTry') : undefined,
    help: s?.openCases ? t('open', { count: n(s.openCases) }) : undefined,
  };
}
