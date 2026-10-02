import { act, renderHook } from '@testing-library/react-native';

import { ApiError, NetworkError } from '@northline/mobile-kit';

import { HandoffError } from '../src/auth/errors';
import { fieldErrors, flowError, splitName, validateCode, validateSignUp } from '../src/auth/rules';
import { translator } from '../src/i18n';
import { useCountdown } from '../src/ui/useCountdown';

const en = translator('en').t;
const fr = translator('fr-CA').t;

describe('the registration rules (docs/spec/validation-rules.md § Registration)', () => {
  it('splits the full name like the consumer site: the last word is the last name', () => {
    expect(splitName('  Mary  Anne Smith ')).toEqual({ firstName: 'Mary Anne', lastName: 'Smith' });
    expect(splitName('Cher')).toEqual({ firstName: 'Cher', lastName: '' });
  });

  it('checks every field with the exact messages, in both languages', () => {
    expect(validateSignUp({ phone: '', fullName: '', email: '', terms: false }, en)).toEqual({
      phone: 'Mobile number is required for verification.',
      fullName: 'First name is required.',
      email: 'Email is required.',
      terms: 'You need to accept the Terms and Privacy Policy.',
    });
    expect(validateSignUp({ phone: '+1 (555) 555-0148', fullName: 'Grace Hopper', email: 'g@example.com', terms: true }, en)).toEqual({});
    expect(validateSignUp({ phone: '555-01', fullName: 'Grace', email: 'g@x', terms: true }, fr)).toEqual({
      phone: 'Entrez un mobile canadien valide, p. ex. +1 403 555 0148.',
      fullName: 'Le nom est obligatoire.',
      email: 'Cette adresse courriel ne semble pas valide.',
    });
    expect(validateCode('', en)).toBe('Enter the 6-digit code.');
    expect(validateCode('12345a', en)).toBe('The code is 6 digits.');
    expect(validateCode(' 123456 ', en)).toBeUndefined();
  });

  it('maps the auth server’s 422 rules to the same messages (French people read French)', () => {
    const e = new ApiError(422, undefined, undefined, [
      { field: 'phone', rule: 'unique', message: 'An account already uses this mobile number. Sign in instead.' },
      { field: 'code', rule: 'mismatch', message: "That code doesn't match." },
    ]);
    expect(fieldErrors(e, fr)).toEqual({ phone: 'Un compte utilise déjà ce numéro de mobile. Connectez-vous plutôt.', code: 'Ce code ne correspond pas. Vérifiez-le et réessayez.' });
    expect(fieldErrors(e, en, 'authErr.signInCodeWrong').code).toBe("That code didn't work. Check it and try again.");
  });

  it('words the flow errors: throttled, rate limited, code not sent, restart, network, hand-off', () => {
    expect(flowError(new ApiError(429, 'otp_throttled', 'Wait', [], 31, { retryAfterSeconds: 31 }), en)).toBe('Too many attempts. Try again in 0:31.');
    expect(flowError(new ApiError(429, 'rate_limited', 'Wait', [], 75), en)).toBe('Too many attempts. Try again in 1:15.');
    expect(flowError(new ApiError(503, 'code_not_sent', 'x'), en, 'voice')).toBe("We couldn't call this number. Try again in a moment, or resend the code by text.");
    expect(flowError(new ApiError(409, 'flow_not_started', 'x'), en)).toBe('That took too long. Start again.');
    expect(flowError(new NetworkError(), en)).toBe("We couldn't reach Northline. Check your connection and try again.");
    expect(flowError(new HandoffError(new Error('no code')), en)).toBe("Signing in didn't finish. Try again.");
    expect(flowError(new ApiError(422, undefined, undefined, [{ field: 'x', message: 'y' }]), en)).toBeUndefined();
  });
});

describe('the resend countdown', () => {
  it('counts down every second and starts again from a new deadline', () => {
    jest.useFakeTimers();
    try {
      let t = 0;
      const now = () => t;
      const { result, rerender } = renderHook(({ at }: { at: number }) => useCountdown(at, now), { initialProps: { at: 45_000 } });
      expect(result.current).toBe(45);
      act(() => {
        t = 44_000;
        jest.advanceTimersByTime(1000);
      });
      expect(result.current).toBe(1);
      act(() => {
        t = 45_000;
        jest.advanceTimersByTime(1000);
      });
      expect(result.current).toBe(0);
      rerender({ at: 90_000 });
      expect(result.current).toBe(45);
    } finally {
      jest.useRealTimers();
    }
  });
});
