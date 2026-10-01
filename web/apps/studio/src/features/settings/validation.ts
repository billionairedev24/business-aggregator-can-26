import { z } from 'zod';
import type { FieldError } from '../../lib/http';
import type { SettingsT } from './messages';

/**
 * Client rules mirroring the server (validation-rules.md where it has them; the rest in docs/DECISIONS.md "Settings &
 * compliance"). Messages come from messages.ts so French users read them in French.
 */
export const GST = /^\d{9}\s?[Rr][Tt]\s?\d{4}$/;
export const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
export const PHONE = /^\+?1?[\s.-]?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$/;

export interface BusinessForm {
  displayName: string; legalName: string; gstNumber: string; serviceArea: string;
  cancellationPolicy: 'flexible' | '12h' | '24h'; autoAccept: string; languages: string[];
}
export const BUSINESS_FIELDS = ['displayName', 'legalName', 'gstNumber', 'serviceArea', 'autoAcceptQuoteCents', 'languages'] as const;

/** Field → message, one per field, most basic rule first. `autoAcceptQuoteCents` is the dollars input. */
export function businessErrors(v: BusinessForm, gstRequired: boolean, t: SettingsT): Record<string, string | undefined> {
  const name = v.displayName.trim();
  const gst = v.gstNumber.trim();
  const amount = v.autoAccept.trim().replace(/^\$/, '');
  return {
    displayName: !name ? t('err_displayName_required') : name.length < 2 ? t('err_displayName_short') : name.length > 80 ? t('err_displayName_long') : undefined,
    legalName: !v.legalName.trim() ? t('err_legalName') : undefined,
    gstNumber: gst && !GST.test(gst) ? t('err_gst_format') : !gst && gstRequired ? t('err_gst_required') : undefined,
    serviceArea: v.serviceArea.trim().length > 200 ? t('err_serviceArea') : undefined,
    autoAcceptQuoteCents: amount && !(/^\d+(\.\d{1,2})?$/.test(amount)) ? t('err_amount') : undefined,
    languages: v.languages.length === 0 ? t('err_languages') : undefined,
  };
}

/** "$150" → 15000 cents; empty → null. */
export const dollarsToCents = (s: string): number | null => {
  const clean = s.trim().replace(/^\$/, '');
  return clean ? Math.round(Number(clean) * 100) : null;
};

export type InviteForm = { by: 'email' | 'phone'; email: string; phone: string; role: string };
export function inviteErrors(v: InviteForm, t: SettingsT): Record<string, string | undefined> {
  const contact = v.by === 'email' ? v.email.trim() : v.phone.trim();
  const key = v.by;
  return {
    [key]: !contact ? t('err_contact') : v.by === 'email' ? (EMAIL.test(contact) ? undefined : t('err_email')) : PHONE.test(contact) ? undefined : t('err_phone'),
    role: !v.role ? t('err_role') : undefined,
  };
}

export const keySchema = (t: SettingsT) => z.object({
  name: z.string().trim().min(1, t('err_keyName')).max(60, t('err_keyNameLong')),
  scopes: z.array(z.string()).min(1, t('err_scopes')),
});

export function webhookErrors(v: { url: string; events: string[] }, t: SettingsT): Record<string, string | undefined> {
  const url = v.url.trim();
  let https = false;
  try { const u = new URL(url); https = u.protocol === 'https:' || (u.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(u.hostname)); } catch { https = false; }
  return {
    url: !url ? t('err_url') : !https ? t('err_https') : undefined,
    events: v.events.length === 0 ? t('err_events') : undefined,
  };
}

/** Server 422 → our translated message where we know the rule, else the server's text. */
export function localizeServerErrors(errors: FieldError[], t: SettingsT): Record<string, string> {
  const known: Record<string, Parameters<SettingsT>[0]> = {
    'displayName:required': 'err_displayName_required', 'displayName:length': 'err_displayName_short', 'legalName:required': 'err_legalName',
    'gstNumber:format': 'err_gst_format', 'gstNumber:required': 'err_gst_required', 'serviceArea:length': 'err_serviceArea',
    'autoAcceptQuoteCents:range': 'err_amount', 'languages:required': 'err_languages',
    'email:required': 'err_contact', 'email:format': 'err_email', 'phone:format': 'err_phone', 'role:required': 'err_role', 'role:allowed': 'err_roleOffer',
    'name:required': 'err_keyName', 'name:length': 'err_keyNameLong', 'scopes:required': 'err_scopes',
    'url:required': 'err_url', 'url:format': 'err_https', 'events:required': 'err_events',
    'allowedOrigins:format': 'err_origin', 'allowedOrigins:size': 'err_origins',
  };
  const out: Record<string, string> = {};
  for (const e of errors) {
    if (out[e.field]) continue;
    const key = known[`${e.field}:${e.rule}`];
    out[e.field] = e.message === 'At most 80 characters.' ? t('err_displayName_long')
      : e.message === 'An invitation is already pending for this contact.' ? t('err_invited')
      : e.message === 'This person is already on your team.' ? t('err_member')
      : key ? t(key) : e.message;
  }
  return out;
}
