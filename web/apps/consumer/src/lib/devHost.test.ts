import { describe, expect, it } from 'vitest';
import { onRequestHost } from './config';

describe('onRequestHost (make mobile-dev: a phone on the Wi-Fi)', () => {
  it('puts the private-network host of the request in place of a loopback origin', () => {
    expect(onRequestHost('http://localhost:9000', '192.168.1.20:3000')).toBe('http://192.168.1.20:9000');
    expect(onRequestHost('http://127.0.0.1:9000', '10.0.2.2:3000')).toBe('http://10.0.2.2:9000');
    expect(onRequestHost('http://localhost:3000', 'macbook.local:3000')).toBe('http://macbook.local:3000');
  });

  it('leaves real hosts, loopback requests and public hosts alone', () => {
    expect(onRequestHost('https://auth.northline.ca', '192.168.1.20:3000')).toBe('https://auth.northline.ca');
    expect(onRequestHost('http://localhost:9000', 'localhost:3000')).toBe('http://localhost:9000');
    expect(onRequestHost('http://localhost:9000', 'evil.example')).toBe('http://localhost:9000');
    expect(onRequestHost('http://localhost:9000', '8.8.8.8:3000')).toBe('http://localhost:9000');
    expect(onRequestHost('http://localhost:9000', undefined)).toBe('http://localhost:9000');
  });
});
