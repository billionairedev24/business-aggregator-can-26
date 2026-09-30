/**
 * Passkeys in the browser (navigator.credentials) ↔ the JSON Spring Security's WebAuthn support speaks: options arrive
 * with base64url strings for every byte field, credentials go back in the WebAuthn Level 3 `toJSON()` shape.
 */

type Json = Record<string, unknown>;

export class PasskeyError extends Error {
  constructor(readonly reason: 'unsupported' | 'cancelled') { super(reason); this.name = 'PasskeyError'; }
}

export const passkeysSupported = () => typeof window !== 'undefined' && typeof window.PublicKeyCredential === 'function' && !!navigator.credentials;

export function base64urlToBuffer(value: string): ArrayBuffer {
  const b64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

export function bufferToBase64url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

const descriptors = (list: unknown) => ((list as Json[] | undefined) ?? []).map(d => ({ ...d, id: base64urlToBuffer(String(d.id)) })) as PublicKeyCredentialDescriptor[];

/** Converts Spring's creation options for navigator.credentials.create(). */
export function toCreationOptions(o: Json): PublicKeyCredentialCreationOptions {
  const user = o.user as Json;
  return {
    ...(o as object),
    challenge: base64urlToBuffer(String(o.challenge)),
    user: { ...user, id: base64urlToBuffer(String(user.id)) },
    excludeCredentials: descriptors(o.excludeCredentials),
  } as PublicKeyCredentialCreationOptions;
}

/** Converts Spring's request options for navigator.credentials.get(). */
export function toRequestOptions(o: Json): PublicKeyCredentialRequestOptions {
  return { ...(o as object), challenge: base64urlToBuffer(String(o.challenge)), allowCredentials: descriptors(o.allowCredentials) } as PublicKeyCredentialRequestOptions;
}

async function call<T>(fn: () => Promise<T | null>): Promise<T> {
  if (!passkeysSupported()) throw new PasskeyError('unsupported');
  try {
    const result = await fn();
    if (!result) throw new PasskeyError('cancelled');
    return result;
  } catch (e) {
    if (e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'AbortError')) throw new PasskeyError('cancelled');
    throw e;
  }
}

/** Creates a passkey; returns the attestation as JSON for POST /api/auth/register/passkey. */
export async function createPasskey(options: Json): Promise<Json> {
  const cred = await call(() => navigator.credentials.create({ publicKey: toCreationOptions(options) }) as Promise<PublicKeyCredential | null>);
  const r = cred.response as AuthenticatorAttestationResponse;
  return {
    id: cred.id,
    rawId: bufferToBase64url(cred.rawId),
    type: cred.type,
    authenticatorAttachment: cred.authenticatorAttachment ?? undefined,
    clientExtensionResults: cred.getClientExtensionResults(),
    response: {
      attestationObject: bufferToBase64url(r.attestationObject),
      clientDataJSON: bufferToBase64url(r.clientDataJSON),
      transports: typeof r.getTransports === 'function' ? r.getTransports() : [],
    },
  };
}

/** Uses a passkey; returns the assertion as JSON for POST /api/auth/sign-in/passkey. */
export async function getPasskey(options: Json): Promise<Json> {
  const cred = await call(() => navigator.credentials.get({ publicKey: toRequestOptions(options) }) as Promise<PublicKeyCredential | null>);
  const r = cred.response as AuthenticatorAssertionResponse;
  return {
    id: cred.id,
    rawId: bufferToBase64url(cred.rawId),
    type: cred.type,
    authenticatorAttachment: cred.authenticatorAttachment ?? undefined,
    clientExtensionResults: cred.getClientExtensionResults(),
    response: {
      authenticatorData: bufferToBase64url(r.authenticatorData),
      clientDataJSON: bufferToBase64url(r.clientDataJSON),
      signature: bufferToBase64url(r.signature),
      userHandle: r.userHandle ? bufferToBase64url(r.userHandle) : undefined,
    },
  };
}
