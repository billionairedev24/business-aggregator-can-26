/**
 * The light, photo-first direction (owner 2026-10-19; the consumer web's phase B): Home's hero and rows, the location
 * sheet (the web's location dialog, the same words), the tiles' muted lines. `{brand}` is the product's name; places
 * are parameters (region-neutral).
 */
export const light = {
  'lt.near': 'Near',
  'lt.setPlace': 'Set your location',

  'lt.home.heroTitle': 'Everything local, from people you can trust',
  'lt.home.heroSub': 'Book verified pros, shop neighbourhood stores and get it all delivered together — you pay only when it’s done.',
  'lt.home.badge': 'Every pro and shop verified',
  'lt.home.tileServices': 'Book a service',
  'lt.home.tileShop': 'Shop local',
  'lt.home.allServices': 'All services',
  'lt.home.allDepartments': 'All departments',
  'lt.home.underway': 'Under way',
  'lt.home.shops': '{n, plural, one {# shop} other {# shops}}',
  'lt.home.pros': '{n, plural, one {# pro} other {# pros}}',
  'lt.home.soon': 'Coming soon',
  'lt.home.sellTitle': 'Sell or offer services on {brand}',
  'lt.home.sellSub': 'Verified local shops and pros only. Apply on the website.',

  'lt.browse.popular': 'Popular categories',
  'lt.browse.filter': 'Find a service',

  'lt.loc.title': 'Choose your location',
  'lt.loc.close': 'Close',
  'lt.loc.currentNone': 'Choose where you are to see what’s available near you.',
  'lt.loc.currentSaved': 'Showing what’s available near {place}.',
  'lt.loc.currentAddress': 'Showing what’s available near your default address, {place}.',
  'lt.loc.currentDefault': 'Showing {place} until you choose your location.',
  'lt.loc.useHere': 'Use my current location',
  'lt.loc.locating': 'Finding where you are…',
  'lt.loc.hereDenied': 'Location is off for {brand}. Search for your address or postal code instead, or allow location in Settings.',
  'lt.loc.hereUnavailable': 'Your phone couldn’t find your position. Search for your address or postal code instead.',
  'lt.loc.hereOutside': '{brand} isn’t available where you are yet. Choose one of our markets below.',
  'lt.loc.search': 'Address or postal code',
  'lt.loc.searchPlaceholder': 'Start typing an address or postal code…',
  'lt.loc.savedAddresses': 'Your addresses',
  'lt.loc.default': 'default',
  'lt.loc.markets': 'Where {brand} is live',
  'lt.loc.marketHint': 'Shows what’s available in {city}',
  'lt.loc.useThis': 'Deliver here',
  'lt.loc.chosen': 'Delivering to',
  'lt.loc.chooseAnother': 'Choose another place',
  'lt.browse.live': '{n, plural, one {# category live} other {# categories live}}',
  'lt.browse.bandSub': 'Every provider is verified before they appear, and every job is paid into escrow — released only when you sign off.',
} as const;
