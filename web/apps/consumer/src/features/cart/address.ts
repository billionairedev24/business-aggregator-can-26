import type { Locale } from '@northline/ui';
import type { AddressInput } from './api';
import { SERVER_FR } from './messages';

/** Server 422 messages in the page's language (orders.domain.CheckoutMessages are English). */
export function localizeServer(message: string, locale: Locale): string {
  if (locale !== 'fr') return message;
  if (SERVER_FR[message]) return SERVER_FR[message]!;
  const notServed = /^We don't deliver to (.+) yet\.$/.exec(message);
  if (notServed) return `Nous ne livrons pas encore à ${notServed[1]}.`;
  const elsewhere = /^(.+) doesn't deliver to (.+)\.$/.exec(message);
  if (elsewhere) return `${elsewhere[1]} ne livre pas à ${elsewhere[2]}.`;
  const restricted = /^Age-restricted items in your cart can't be delivered to (.+)\. Remove them to continue\.$/.exec(message);
  if (restricted) return `Les articles soumis à un âge minimal de votre panier ne peuvent pas être livrés en ${restricted[1]}. Retirez-les pour continuer.`;
  const left = /^Only (\d+) left\.$/.exec(message);
  if (left) return `Plus que ${left[1]}.`;
  return message;
}

/**
 * Client checks with the server's messages (orders.domain.CheckoutMessages).
 * @param provinces the region model's province codes (any two-letter code until it has loaded; the server checks too)
 */
export function addressErrors(a: AddressInput, provinces: readonly string[] = []): Record<string, string> {
  const e: Record<string, string> = {};
  if (a.addressId) return e;
  if (!a.street?.trim() || a.street.trim().length > 120) e['address.street'] = 'Enter the street address.';
  if (a.unit && a.unit.trim().length > 20) e['address.unit'] = 'Keep the unit under 20 characters.';
  if (!a.city?.trim() || a.city.trim().length > 60) e['address.city'] = 'Enter the city.';
  if (!a.province || !/^[A-Z]{2}$/.test(a.province) || (provinces.length > 0 && !provinces.includes(a.province))) e['address.province'] = "Choose a Canadian province or territory.";
  if (!/^[A-Z]\d[A-Z] ?\d[A-Z]\d$/.test((a.postal ?? '').trim().toUpperCase().replace('-', ' '))) e['address.postal'] = 'Enter a Canadian postal code, like T2P 1B5.';
  if (a.note && a.note.trim().length > 200) e['address.note'] = 'Keep delivery notes under 200 characters.';
  return e;
}

export const trimAddress = (a: AddressInput): AddressInput => ({
  street: a.street?.trim(), unit: a.unit?.trim() || undefined, city: a.city?.trim(), province: a.province,
  postal: a.postal?.trim().toUpperCase(), note: a.note?.trim() || undefined,
});
