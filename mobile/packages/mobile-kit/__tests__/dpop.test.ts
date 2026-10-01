import { p256 } from '@noble/curves/nist.js';

import { base64url, fromBase64url, fromUtf8, utf8 } from '../src/bytes';
import { codeChallenge, codeVerifier } from '../src/auth/pkce';
import { SoftwareDeviceKey, createDeviceKey, deleteDeviceKey, loadDeviceKey, thumbprintOf } from '../src/dpop/key';
import { ServerClock, accessTokenHash, createProof, htu } from '../src/dpop/proof';
import { memorySecureStorage } from '../src/storage/secure';
import { verifyProof } from './support/fakeServers';

describe('bytes', () => {
  it('base64url round-trips without padding', () => {
    for (const n of [0, 1, 2, 3, 31, 32, 33, 64]) {
      const bytes = Uint8Array.from({ length: n }, (_, i) => (i * 37 + 11) & 255);
      const text = base64url(bytes);
      expect(text).not.toMatch(/[=+/]/);
      expect([...fromBase64url(text)]).toEqual([...bytes]);
    }
    expect(base64url(utf8('hello?'))).toBe('aGVsbG8_');
  });

  it('decodes UTF-8 by hand', () => {
    expect(fromUtf8(utf8('Livré à 19 h — ✓ 🚲'))).toBe('Livré à 19 h — ✓ 🚲');
  });
});

describe('PKCE', () => {
  it('derives the S256 challenge of RFC 7636 appendix B', () => {
    expect(codeChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('makes 43-character random verifiers', () => {
    const v = codeVerifier();
    expect(v).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(codeVerifier()).not.toBe(v);
  });
});

describe('the device key', () => {
  it('is a P-256 key whose public JWK and thumbprint the server can bind tokens to', () => {
    const key = SoftwareDeviceKey.generate();
    expect(key.jwk.kty).toBe('EC');
    expect(key.jwk.crv).toBe('P-256');
    expect(fromBase64url(key.jwk.x)).toHaveLength(32);
    expect(fromBase64url(key.jwk.y)).toHaveLength(32);
    expect(key.jwk).not.toHaveProperty('d');
    expect(key.thumbprint).toBe(thumbprintOf(key.jwk));
    expect(key.thumbprint).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it('signs ES256 (R ‖ S, 64 bytes) that verifies with the public key', async () => {
    const key = SoftwareDeviceKey.generate();
    const sig = await key.sign(utf8('payload'));
    expect(sig).toHaveLength(64);
    const pub = new Uint8Array([4, ...fromBase64url(key.jwk.x), ...fromBase64url(key.jwk.y)]);
    expect(p256.verify(sig, utf8('payload'), pub, { prehash: true, lowS: false })).toBe(true);
  });

  it('is kept in secure storage for the sign-in and deleted at sign-out', async () => {
    const storage = memorySecureStorage();
    expect(await loadDeviceKey(storage)).toBeNull();
    const key = await createDeviceKey(storage);
    expect((await loadDeviceKey(storage))?.thumbprint).toBe(key.thumbprint);
    await deleteDeviceKey(storage);
    expect(await loadDeviceKey(storage)).toBeNull();
  });

  it('drops a damaged stored key instead of using it', async () => {
    const storage = memorySecureStorage();
    await storage.set('nl.dpop.key.v1', base64url(new Uint8Array(32))); // zero is not a valid secret
    expect(await loadDeviceKey(storage)).toBeNull();
    expect(storage.values.size).toBe(0);
  });
});

describe('DPoP proofs', () => {
  it('carry htm, htu without the query, iat, a new jti, the nonce for the auth server and ath for the api', async () => {
    const key = SoftwareDeviceKey.generate();
    const clock = new ServerClock(() => 1_700_000_000_000);
    const seen = new Set<string>();
    const a = verifyProof(
      await createProof(key, clock, { method: 'get', url: 'https://api.x/api/v1/courier/run?x=1#y', accessToken: 'tok' }),
      seen,
    );
    expect(a.header.jwk).toEqual(key.jwk);
    expect(a.claims).toMatchObject({ htm: 'GET', htu: 'https://api.x/api/v1/courier/run', iat: 1_700_000_000, ath: accessTokenHash('tok') });
    expect(a.claims.nonce).toBeUndefined();
    const b = verifyProof(await createProof(key, clock, { method: 'POST', url: 'https://auth.x/oauth2/token', nonce: 'n1' }), seen);
    expect(b.claims.nonce).toBe('n1');
    expect(b.claims.ath).toBeUndefined();
    expect(b.claims.jti).not.toBe(a.claims.jti);
  });

  it('take iat from the server clock (the Date header), ignoring sub-second noise', () => {
    let now = Date.parse('2026-10-01T12:00:00Z');
    const clock = new ServerClock(() => now);
    clock.observe('Thu, 01 Oct 2026 12:02:00 GMT'); // the phone is two minutes slow
    expect(clock.seconds()).toBe(Date.parse('2026-10-01T12:02:00Z') / 1000);
    now += 10_000;
    expect(clock.seconds()).toBe(Date.parse('2026-10-01T12:02:10Z') / 1000);
    clock.observe(new Date(now + 800).toUTCString());
    expect(clock.offset).toBe(0);
    clock.observe('not a date');
    expect(clock.offset).toBe(0);
  });

  it('htu keeps the path', () => {
    expect(htu('https://api.x/a/b')).toBe('https://api.x/a/b');
    expect(htu('https://api.x/a?b=c')).toBe('https://api.x/a');
  });

  it('decode as JSON', async () => {
    const key = SoftwareDeviceKey.generate();
    const jws = await createProof(key, new ServerClock(), { method: 'POST', url: 'https://a/b' });
    const header = JSON.parse(fromUtf8(fromBase64url(jws.split('.')[0]!)));
    expect(header.typ).toBe('dpop+jwt');
  });
});
