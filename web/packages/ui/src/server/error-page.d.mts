export type ErrorLocale = 'en' | 'fr';
export type ErrorKind = 'notFound' | 'unreachable' | 'error';
export type ErrorAudience = 'site' | 'app';
export interface ErrorDetail { name: string; message: string; stack: string; hints: string[] }

export declare const ERROR_MESSAGES: Record<ErrorLocale, Record<string, string>>;
export declare function errorKind(status: number): ErrorKind;
export declare function errorReference(): string;
export declare function errorLocale(opts?: { url?: string; cookie?: string | null; acceptLanguage?: string | null }): ErrorLocale;
export declare function wantsJson(opts: { path?: string; accept?: string | null }): boolean;
export declare function errorJson(status: number, ref: string): string;
export declare function devHints(error: unknown): string[];
export declare function errorDetail(error: unknown): ErrorDetail;
export declare function logError(entry: { ref: string; status: number; method?: string; url?: string; error: unknown; source?: string }, log?: (line: string) => void): void;
export declare function renderErrorPage(opts?: {
  status?: number; locale?: ErrorLocale; ref?: string; url?: string; audience?: ErrorAudience; detail?: ErrorDetail;
  nonce?: string; supportEmail?: string | null; homeHref?: string; reload?: boolean;
}): string;
type HeaderBag = { get(name: string): string | null } | Record<string, string | string[] | undefined>;
export interface ErrorAnswer { status: number; headers: Record<string, string>; body: string; ref: string }
export declare function errorResponseParts(opts: {
  status: number; error?: unknown; request?: { url?: string; method?: string; headers?: HeaderBag }; dev?: boolean;
  audience?: ErrorAudience; nonce?: string; supportEmail?: string | null; ref?: string; log?: (line: string) => void; source?: string; reload?: boolean;
}): ErrorAnswer;
/** For Vite's `server.proxy[path].configure`. */
export declare function proxyErrors(opts?: { audience?: ErrorAudience; service?: string }): (proxy: object, options?: { target?: unknown }) => void;
