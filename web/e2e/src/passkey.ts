import type { CDPSession, Page } from '@playwright/test';
import type { PasskeyCredential } from './env';

/**
 * A platform authenticator for one page (Chrome DevTools Protocol `WebAuthn`): passkeys are created and used without a
 * prompt, as Touch ID with user verification would. Its credentials can be exported after registration and imported
 * into the next page, so a consumer who registered in the setup signs in and steps up with the same passkey later.
 */
export class VirtualAuthenticator {
  private constructor(private readonly cdp: CDPSession, private readonly id: string) {}

  static async attach(page: Page): Promise<VirtualAuthenticator> {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('WebAuthn.enable');
    const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
      options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true },
    });
    return new VirtualAuthenticator(cdp, authenticatorId);
  }

  async credentials(): Promise<PasskeyCredential[]> {
    const { credentials } = await this.cdp.send('WebAuthn.getCredentials', { authenticatorId: this.id });
    return credentials as PasskeyCredential[];
  }

  async add(credential: PasskeyCredential): Promise<void> {
    await this.cdp.send('WebAuthn.addCredential', { authenticatorId: this.id, credential });
  }
}
