import type { ApiClient } from '@northline/mobile-kit';

/** The courier API of S-86/S-88 (docs/runbooks/fulfilment.md § Courier app API). */

export type CourierStatus = 'offline' | 'available' | 'on_run';
export type ShiftState = 'scheduled' | 'on' | 'done';
export type StopKind = 'pickup' | 'dropoff';
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
  markets: Array<{ id: string; city: string; province: string; timeZone: string }>;
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

  async dropoff(stopId: string, proof: ProofKind, pin: string | undefined, idempotencyKey: string): Promise<Run> {
    return (await this.api.post<Run>(`/courier/stops/${encodeURIComponent(stopId)}/dropoff`, {
      json: proof === 'pin' ? { proof, pin } : { proof },
      idempotencyKey,
    }))!;
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

/** The market's IANA zone from the region model; the platform's when the market isn't listed. */
export function marketZone(regions: Regions | undefined, market: string | null | undefined): string | undefined {
  if (!regions) return undefined;
  const m = regions.markets.find((x) => x.id === market || x.city === market);
  return m?.timeZone ?? regions.platformTimeZone;
}
