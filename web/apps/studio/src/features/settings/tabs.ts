/** Settings tabs (`?tab=`). Its own module (S-69): the route's search validation is in the initial bundle, the api isn't. */
export const SETTINGS_TABS = ['business', 'team', 'security', 'notifications', 'api'] as const;
export type SettingsTab = (typeof SETTINGS_TABS)[number];
