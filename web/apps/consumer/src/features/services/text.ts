import type { Translate } from '@northline/ui';

/** Looks up a message whose key is built at run time (per booking type / group); `fallback` when it doesn't exist. */
export function dyn<K extends string>(t: Translate<K>, key: string, fallback = '', values?: Parameters<Translate<K>>[1]): string {
  const text = t(key as K, values);
  return text === key ? fallback : text;
}
