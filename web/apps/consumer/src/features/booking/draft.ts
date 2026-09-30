import { useCallback, useEffect, useState } from 'react';

/**
 * What the customer entered in the wizard. It survives a reload and the trip to the sign-in page (sessionStorage,
 * per business; never the access instructions' server copy — this stays in the browser tab) and is cleared once
 * booked. Chip answers are kept as the option's index; `values()` turns them into the English values the api stores.
 */
export interface Draft {
  serviceId?: string;
  description: string;
  urgency?: number;
  vehicle: { year: string; make: string; model: string; plate: string; fuel?: number };
  home: { type: string; beds: string; baths: string; size: string; addons: number[]; pets?: number };
  hours?: number;
  stylistNote: string;
  consult: { goal?: number; timeline?: number; priceRange?: number; areas: string; preapproved?: number; meeting?: number };
  addressLine: string;
  unit: string;
  spot?: number;
  accessNote: string;
  present?: number;
  contactPhone: string;
  contactPref: number;
  day?: string;
  startsAt?: string;
  flexibility?: number;
  agreePolicies: boolean;
  agreeTerms: boolean;
  hold?: { holdId: string; bookingId: string; startsAt: string; expiresAt: string };
}

export const EMPTY: Draft = {
  description: '', vehicle: { year: '', make: '', model: '', plate: '' },
  home: { type: '', beds: '', baths: '', size: '', addons: [] }, stylistNote: '',
  consult: { areas: '' }, addressLine: '', unit: '', accessNote: '', contactPhone: '', contactPref: 0,
  agreePolicies: false, agreeTerms: false,
};

const key = (slug: string) => `nl.book.${slug}`;

/** The draft for one business; starts empty (server render = first client render) and loads after mount. */
export function useDraft(slug: string) {
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    try {
      const raw = sessionStorage.getItem(key(slug));
      if (raw) setDraft({ ...EMPTY, ...(JSON.parse(raw) as Partial<Draft>) });
    } catch { /* private mode: keep it in memory */ }
    setLoaded(true);
  }, [slug]);
  const update = useCallback((patch: Partial<Draft>) => {
    setDraft(prev => {
      const next = { ...prev, ...patch };
      try { sessionStorage.setItem(key(slug), JSON.stringify(next)); } catch { /* ignore */ }
      return next;
    });
  }, [slug]);
  const clear = useCallback(() => {
    try { sessionStorage.removeItem(key(slug)); } catch { /* ignore */ }
    setDraft(EMPTY);
  }, [slug]);
  return { draft, update, clear, loaded };
}

/** The stored values of the chip groups (English: what the provider's Studio shows). */
export const VALUES = {
  urgency: ['As soon as possible', 'This week', 'Next week', 'Flexible'],
  fuel: ['Gas', 'Diesel', 'Hybrid', 'Electric'],
  home: ['Apartment / condo', 'Townhouse', 'Detached house', 'Office'],
  size: ['Under 800 sq ft', '800–1,500', '1,500–2,500', '2,500+'],
  addons: ['Inside fridge', 'Inside oven', 'Windows (inside)', 'Laundry', 'Balcony'],
  pets: ['None', 'Dog', 'Cat', 'Other'],
  goal: ['Buy', 'Sell', 'Buy & sell', 'Rent'],
  timeline: ['Within 3 months', '3–6 months', '6–12 months', 'Just exploring'],
  priceRange: ['Under $400k', '$400k – $600k', '$600k – $900k', '$900k+'],
  preapproved: ['Yes', 'Not yet', 'Need a broker'],
  meeting: ['At my home', 'Their office', 'Video call'],
  spotVehicle: ['Driveway', 'Street parking', 'Underground parkade', 'Workplace lot'],
  spotHome: ['Front door', 'Side or back door', 'Concierge / buzzer', 'Lockbox'],
  present: ["Yes, I'll be there", 'Someone else will', 'No — keys left as described'],
  contactPref: ['In-app message', 'Text message', 'Phone call'],
  flexibility: ['Exact time', '± 1 hour is fine', 'Any time that day'],
} as const;

const pick = (list: readonly string[], i: number | undefined) => (i === undefined ? undefined : list[i]);

/** Design 06: estimated hours of a home clean from the bedrooms, + 0.5 h per add-on. */
export function homeHours(beds: string, addons: number): number {
  if (!beds) return 0;
  return (({ '1': 2.5, '2': 3, '3': 4, '4+': 5 } as Record<string, number>)[beds] ?? 3) + addons * 0.5;
}

/** The checkout request (POST /api/v1/me/bookings/checkout). */
export function toRequest(d: Draft, holdId: string, serviceId: string, opts: { vehicle: boolean; kind: string; cleaning: boolean }) {
  const spots = opts.vehicle ? VALUES.spotVehicle : VALUES.spotHome;
  const description = [d.description.trim(), opts.kind === 'appointment' ? d.stylistNote.trim() : ''].filter(Boolean).join('\n\n');
  return {
    holdId, serviceId, description: description || undefined, urgency: pick(VALUES.urgency, d.urgency),
    vehicle: opts.vehicle ? { year: d.vehicle.year, make: d.vehicle.make, model: d.vehicle.model, plate: d.vehicle.plate || undefined, fuel: pick(VALUES.fuel, d.vehicle.fuel) } : undefined,
    home: opts.kind === 'home' && opts.cleaning ? { type: d.home.type, beds: d.home.beds, baths: d.home.baths || undefined, size: d.home.size || undefined, addons: d.home.addons.map(i => VALUES.addons[i]), pets: pick(VALUES.pets, d.home.pets) } : undefined,
    consult: opts.kind === 'consult' ? { goal: pick(VALUES.goal, d.consult.goal), timeline: pick(VALUES.timeline, d.consult.timeline), priceRange: pick(VALUES.priceRange, d.consult.priceRange), areas: d.consult.areas || undefined, preapproved: pick(VALUES.preapproved, d.consult.preapproved), meeting: pick(VALUES.meeting, d.consult.meeting) } : undefined,
    hours: opts.kind === 'home' ? d.hours : undefined,
    addressLine: d.addressLine.trim() || undefined, unit: d.unit.trim() || undefined,
    spot: pick(spots, d.spot), accessNote: d.accessNote.trim() || undefined, present: pick(VALUES.present, d.present),
    contactPhone: d.contactPhone.trim() || undefined, contactPreference: VALUES.contactPref[d.contactPref],
    flexibility: pick(VALUES.flexibility, d.flexibility),
    agreePolicies: d.agreePolicies, agreeTerms: d.agreeTerms,
  };
}
