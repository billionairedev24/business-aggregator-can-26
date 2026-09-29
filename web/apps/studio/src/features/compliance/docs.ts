import { TIME_ZONE, type Locale, type TagTone } from '@northline/ui';
import type { ComplianceDoc, Requirement } from './api';
import type { ComplianceT } from './messages';

/** "Jan 2027" / « janv. 2027 » in America/Edmonton. */
export function monthYear(iso: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { timeZone: TIME_ZONE, month: 'short', year: 'numeric' }).format(new Date(iso));
}

/** "Aug 31" in America/Edmonton. */
export function monthDay(iso: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { timeZone: TIME_ZONE, month: 'short', day: 'numeric' }).format(new Date(iso));
}

/** "2027-01" (Settings › Business › Licences & insurance). */
export function yearMonth(iso: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: TIME_ZONE, year: 'numeric', month: '2-digit' }).formatToParts(new Date(iso));
  return `${parts.find(p => p.type === 'year')?.value}-${parts.find(p => p.type === 'month')?.value}`;
}

const isGst = (d: ComplianceDoc) => d.checkKey === 'gst' || (d.checkType === 'registry' && d.registry === 'CRA');
const isPrivacy = (d: ComplianceDoc) => d.checkType === 'attestation' && d.registry === 'PIPEDA';

/** The row's name: the recorded label, else one derived from type / registry / reference. */
export function docName(d: ComplianceDoc, t: ComplianceT): string {
  if (d.label) return d.label;
  if (isGst(d)) return d.reference ? `${t('name_gst')} ${d.reference.replace(/^\d{9}\s?/, '')}` : t('name_gst');
  if (isPrivacy(d)) return t('name_privacy');
  switch (d.checkType) {
    case 'licence': return (d.registry ? t('name_licence', { registry: d.registry }) : t('name_licenceNoRegistry')) + (d.reference && /\d/.test(d.reference) ? ` ${d.reference}` : '');
    case 'wcb': return t('name_wcb');
    case 'insurance': return t('name_insurance');
    case 'ahs_permit': return t('name_ahs_permit') + (d.reference ? ` · #${d.reference}` : '');
    case 'food_cert': return t('name_food_cert');
    case 'inspection': return t('name_inspection');
    case 'attestation': return t('name_attestation');
    default: return t('name_registry');
  }
}

/** Short name for the page title ("One item due: WCB clearance letter"). */
export function docShortName(d: ComplianceDoc, t: ComplianceT): string {
  if (d.checkType === 'wcb') return t('name_wcbShort');
  if (d.checkType === 'insurance') return t('name_insurance');
  if (d.checkType === 'licence') return d.registry ? t('name_licence', { registry: d.registry }) : t('name_licenceNoRegistry');
  if (d.checkType === 'ahs_permit') return t('name_ahs_permit');
  if (d.checkType === 'food_cert') return t('name_food_cert');
  if (d.checkType === 'inspection') return t('name_inspection');
  return docName(d, t);
}

/** Why the row is required (small line under the name). */
export function docNote(d: ComplianceDoc, t: ComplianceT): string {
  if (isGst(d)) return t('note_gst');
  if (isPrivacy(d)) return t('note_privacy');
  switch (d.checkType) {
    case 'licence':
      if (d.registry === 'AMVIC') return t('note_amvic');
      if (d.registry === 'Red Seal') return t('note_redSeal');
      if (d.registry === 'AGLC') return t('note_aglc');
      return t('note_licence');
    case 'wcb': return t('note_wcb');
    case 'insurance': return t('note_insurance');
    case 'ahs_permit': return t('note_ahs');
    case 'food_cert': return t('note_foodCert');
    case 'inspection': return t('note_inspection');
    case 'attestation': return t('note_attestation');
    default: return '';
  }
}

export interface DocState { text: string; tone: TagTone; action: boolean }

/** Tag text + tone, and whether the owner should upload (design: due rows carry an Upload button). */
export function docState(d: ComplianceDoc, t: ComplianceT, locale: Locale): DocState {
  switch (d.status) {
    case 'expired': return { text: d.expiresAt ? t(d.checkType === 'wcb' ? 'doc_expired' : 'doc_expiredDoc', { date: monthDay(d.expiresAt, locale) }) : t('doc_todo'), tone: 'accent-2', action: true };
    case 'todo': return { text: t('doc_todo'), tone: 'accent-2', action: true };
    case 'rejected': return { text: t('doc_rejected'), tone: 'accent-2', action: true };
    case 'submitted': return { text: t('doc_submitted'), tone: 'neutral', action: false };
    default: break;
  }
  if (d.dueSoon && d.expiresAt) return { text: t('doc_dueSoon', { date: monthDay(d.expiresAt, locale) }), tone: 'highlight', action: true };
  if (isGst(d)) return { text: t('doc_gst'), tone: 'accent', action: false };
  if (d.checkType === 'attestation') return { text: d.verifiedAt ? t('doc_signed', { month: monthYear(d.verifiedAt, locale) }) : t('doc_verified'), tone: 'accent', action: false };
  if (d.reference === 'none') return { text: t('doc_none'), tone: 'accent', action: false };
  if (d.reference === 'not_applicable') return { text: t('doc_notApplicable'), tone: 'neutral', action: false };
  if (d.expiresAt) return { text: t(d.checkType === 'insurance' ? 'doc_expires' : 'doc_renews', { month: monthYear(d.expiresAt, locale) }), tone: 'accent', action: false };
  return { text: t('doc_verified'), tone: 'accent', action: false };
}

/** "Due in 12 days" etc. for a Stripe requirement. */
export function requirementState(r: Requirement, t: ComplianceT, now = Date.now()): { text: string; tone: TagTone } {
  switch (r.state) {
    case 'verified': return { text: t('state_verified'), tone: 'accent' };
    case 'pending': return { text: t('state_pending'), tone: 'neutral' };
    case 'past_due': return { text: t('state_past_due'), tone: 'accent-2' };
    case 'due': {
      if (!r.dueAt) return { text: t('state_due'), tone: 'accent-2' };
      const days = Math.ceil((new Date(r.dueAt).getTime() - now) / 86_400_000);
      return { text: days <= 0 ? t('state_dueToday') : t('state_dueIn', { n: days }), tone: 'accent-2' };
    }
  }
}

/** "784512369" → "78451 2369" (the design groups the CRA business number 5 + 4). */
export const groupBn = (bn: string) => (/^\d{9}$/.test(bn) ? `${bn.slice(0, 5)} ${bn.slice(5)}` : bn);
