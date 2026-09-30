import { AppleMark, federationUrl, GoogleMark } from '@northline/auth-kit';

/** Design 06: "Apple" and "Google" side by side (S-18 federation, coming back to the consumer site). */
export function SocialButtons() {
  return (
    <div className="nl-auth-social">
      <a className="btn btn-secondary" href={federationUrl('apple', 'consumer')}><AppleMark />Apple</a>
      <a className="btn btn-secondary" href={federationUrl('google', 'consumer')}><GoogleMark />Google</a>
    </div>
  );
}
