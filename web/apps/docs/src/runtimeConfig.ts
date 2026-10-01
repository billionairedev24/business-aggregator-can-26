/** /config.js: settings nginx writes at container start (web/docker/docs.conf.template), read in the browser. */
export interface RuntimeConfig {
  /** "<label>|<url>" entries, e.g. "dev api|https://api.dev.northline.ca/docs" — empty in production. */
  swagger?: string[];
}

declare global {
  interface Window {
    __NL_DOCS__?: RuntimeConfig;
  }
}

/** The services' viewer links of the runtime configuration; malformed or non-http(s) entries are skipped. */
export function swaggerTargets(config: RuntimeConfig | undefined): { label: string; url: string }[] {
  return (config?.swagger ?? [])
    .map((entry) => entry.split('|'))
    .filter((parts): parts is [string, string] => parts.length === 2 && /^https?:\/\//.test(parts[1] ?? ''))
    .map(([label, url]) => ({ label, url }));
}
