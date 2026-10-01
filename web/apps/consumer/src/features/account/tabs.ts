/** The account's tabs in design 06 order (`acctTabs`); `sell` is its own page (S-61). */
export const ACCOUNT_TABS = ['wallet', 'payments', 'profile', 'addresses', 'favourites', 'security', 'notifications', 'language', 'dietary', 'plus', 'help'] as const;
export type AccountTab = (typeof ACCOUNT_TABS)[number];

export const isAccountTab = (v: unknown): v is AccountTab => typeof v === 'string' && (ACCOUNT_TABS as readonly string[]).includes(v);

/** `/account?tab=…` (plus extra search parameters, e.g. a case). */
export const tabHref = (tab: AccountTab, extra: Record<string, string> = {}) =>
  `/account?${new URLSearchParams({ tab, ...extra }).toString()}`;
