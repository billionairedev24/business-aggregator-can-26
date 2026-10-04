import type { ApiClient } from '@northline/mobile-kit';

/** The courier API of S-86/S-88 (docs/runbooks/fulfilment.md § Courier app API). */

export type CourierStatus = 'offline' | 'available' | 'on_run';
export type ShiftState = 'scheduled' | 'on' | 'done';
/** `return`: a refused age-restricted order going back to the business (2026-10-04). */
export type StopKind = 'pickup' | 'dropoff' | 'return';
export type StopState = 'pending' | 'arrived' | 'done';
export type ProofKind = 'photo' | 'signature' | 'pin';

export interface Shift {
  id: string;
  startsAt: string;
  endsAt: string;
  state: ShiftState;
  startedAt: string | null;
  endedAt: string | null;
}

export interface Courier {
  courierId: string;
  market: string | null;
  vehicle: string | null;
  status: CourierStatus;
  shift: Shift | null;
}

export interface Place {
  merchantId: string;
  name: string;
  address: string | null;
  lat: number | null;
  lng: number | null;
}

export interface Dropoff {
  street: string;
  unit: string | null;
  city: string | null;
  postal: string | null;
  note: string | null;
  lat: number | null;
  lng: number | null;
}

/** 2026-10-04: an age-restricted drop-off — the age to check on government photo ID and whose name it must show. */
export interface IdCheck {
  age: number;
  recipient: string | null;
}

/** The courier's three confirmations at the door (never the ID itself: no photo, number or date of birth). */
export interface IdCheckAnswer {
  idChecked: boolean;
  recipientMatches: boolean;
  ofAge: boolean;
}

/** Why an age-restricted order wasn't handed over (the api's list). */
export const REFUSE_REASONS = ['no_id', 'underage', 'mismatch', 'nobody_of_age', 'intoxicated', 'other'] as const;
export type RefuseReason = (typeof REFUSE_REASONS)[number];

export interface Stop {
  id: string;
  seq: number;
  kind: StopKind;
  state: StopState;
  orderId: string;
  orderRef: string | null;
  eta: string | null;
  arrivedAt: string | null;
  doneAt: string | null;
  place: Place | null;
  dropoff: Dropoff | null;
  packed: boolean;
  proofKind: string | null;
  /** Age-restricted drop-off (2026-10-04); absent from older servers. */
  idCheck?: IdCheck | null;
}

export interface Run {
  id: string;
  label: string | null;
  part: number;
  kind: string;
  state: 'planned' | 'loading' | 'en_route' | 'done';
  market: string;
  startsAt: string | null;
  endsAt: string | null;
  stops: Stop[];
}

export interface Ping {
  acceptedAt: string;
  nextAfterMs: number;
}

/** A proof file on the phone: a camera photo (JPEG) or the signature (PNG). */
export interface ProofFile {
  uri: string;
  type: 'image/jpeg' | 'image/png';
  name: string;
}

export interface Regions {
  platformTimeZone: string;
  /** S-116: `frenchFirst` — the place's language rule (region configuration); absent from older servers. */
  provinces?: Array<{ code: string; frenchFirst?: boolean }>;
  markets: Array<{ id: string; city: string; province: string; timeZone: string; frenchFirst?: boolean }>;
}

export class CourierApi {
  constructor(
    private readonly api: ApiClient,
    /** Public endpoints (the region model) go without a token. */
    private readonly publicFetch: typeof fetch = (...a) => fetch(...a),
  ) {}

  async me(): Promise<Courier> {
    return (await this.api.get<Courier>('/courier/me'))!;
  }

  async shifts(): Promise<Shift[]> {
    return (await this.api.get<{ items: Shift[] }>('/courier/shifts'))?.items ?? [];
  }

  async startShift(id: string): Promise<Shift> {
    return (await this.api.post<Shift>(`/courier/shifts/${encodeURIComponent(id)}/start`))!;
  }

  async endShift(id: string): Promise<Shift> {
    return (await this.api.post<Shift>(`/courier/shifts/${encodeURIComponent(id)}/end`))!;
  }

  /** The open run, or null (204) without one. */
  async run(): Promise<Run | null> {
    return this.api.get<Run>('/courier/run');
  }

  async arrive(stopId: string, idempotencyKey: string): Promise<Run> {
    return (await this.api.post<Run>(`/courier/stops/${encodeURIComponent(stopId)}/arrive`, { idempotencyKey }))!;
  }

  async pickup(stopId: string, scanOk: boolean, idempotencyKey: string): Promise<Run> {
    return (await this.api.post<Run>(`/courier/stops/${encodeURIComponent(stopId)}/pickup`, { json: { scanOk }, idempotencyKey }))!;
  }

  async uploadProof(stopId: string, kind: 'photo' | 'signature', file: ProofFile, idempotencyKey: string): Promise<Run> {
    const form = new FormData();
    form.append('kind', kind);
    // React Native's FormData streams a file from its uri
    form.append('file', { uri: file.uri, type: file.type, name: file.name } as unknown as Blob);
    return (await this.api.post<Run>(`/courier/stops/${encodeURIComponent(stopId)}/proof`, { form, idempotencyKey }))!;
  }

  async dropoff(stopId: string, proof: ProofKind, pin: string | undefined, idempotencyKey: string, idCheck?: IdCheckAnswer): Promise<Run> {
    return (await this.api.post<Run>(`/courier/stops/${encodeURIComponent(stopId)}/dropoff`, {
      json: { proof, ...(proof === 'pin' ? { pin } : {}), ...(idCheck ? { idCheck } : {}) },
      idempotencyKey,
    }))!;
  }

  /** An age-restricted drop-off that can't be handed over: the order goes back to the business (a return stop). */
  async refuse(stopId: string, reason: RefuseReason, idempotencyKey: string): Promise<Run> {
    return (await this.api.post<Run>(`/courier/stops/${encodeURIComponent(stopId)}/refuse`, { json: { reason }, idempotencyKey }))!;
  }

  /** The refused order is back at the business. */
  async returned(stopId: string, idempotencyKey: string): Promise<Run> {
    return (await this.api.post<Run>(`/courier/stops/${encodeURIComponent(stopId)}/returned`, { idempotencyKey }))!;
  }

  async ping(lat: number, lng: number, heading?: number | null): Promise<Ping> {
    const body: Record<string, number> = { lat, lng };
    if (heading != null && heading >= 0 && heading <= 360) body.heading = heading;
    return (await this.api.post<Ping>('/courier/location', { json: body }))!;
  }

  /** The region model (public, S-134): the market's time zone for the run's times. */
  async regions(lang: string): Promise<Regions> {
    const res = await this.publicFetch(`${this.api.baseUrl}/geo/regions?lang=${lang.startsWith('fr') ? 'fr' : 'en'}`, {
      headers: { Accept: 'application/json' },
    });
    if (!res.ok) throw new Error(`regions ${res.status}`);
    return (await res.json()) as Regions;
  }
}

/**
 * S-116 (Loi 96 readiness): whether the courier's market is French-first — the market's own rule, else its province's
 * (region configuration; the app never names a place). Unknown market: no.
 */
export function frenchFirst(regions: Regions | undefined, market: string | null | undefined): boolean {
  if (!regions || !market) return false;
  const m = regions.markets.find((x) => x.id === market || x.city === market);
  if (!m) return false;
  return m.frenchFirst ?? regions.provinces?.find((p) => p.code === m.province)?.frenchFirst ?? false;
}

/** The market's IANA zone from the region model; the platform's when the market isn't listed. */
export function marketZone(regions: Regions | undefined, market: string | null | undefined): string | undefined {
  if (!regions) return undefined;
  const m = regions.markets.find((x) => x.id === market || x.city === market);
  return m?.timeZone ?? regions.platformTimeZone;
}
