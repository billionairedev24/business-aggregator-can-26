import { expect, type APIResponse, type BrowserContext } from '@playwright/test';

/**
 * Calls an app's own `/api` as the person signed in to that browser context — through the app's BFF, with the
 * session cookie and the CSRF double-submit header, exactly as the app's fetches do. For the few steps a journey needs
 * that have no screen in that app (a customer's sign-off is in the mobile app; the courier's run is in the courier app).
 */
export class Bff {
  constructor(private readonly context: BrowserContext, private readonly origin: string) {}

  private async xsrf(): Promise<Record<string, string>> {
    const cookies = await this.context.cookies(this.origin);
    const token = cookies.find(c => c.name === 'XSRF-TOKEN' || c.name === '__Host-XSRF-TOKEN')?.value;
    return token ? { 'X-XSRF-TOKEN': token } : {};
  }

  async get<T = unknown>(path: string): Promise<T> {
    return ok<T>(await this.context.request.get(this.origin + path), 'GET', path);
  }

  async post<T = unknown>(path: string, data?: unknown, headers: Record<string, string> = {}): Promise<{ status: number; body: T }> {
    const res = await this.context.request.post(this.origin + path, {
      data: data ?? {},
      headers: { ...(await this.xsrf()), ...headers },
    });
    const body = await ok<T>(res, 'POST', path);
    return { status: res.status(), body };
  }
}

async function ok<T>(res: APIResponse, method: string, path: string): Promise<T> {
  expect(res.status(), `${method} ${path}: ${(await res.text()).slice(0, 300)}`).toBeLessThan(400);
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
