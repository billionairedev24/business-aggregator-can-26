import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { configurePlatformTimeZone } from '@northline/ui';

configurePlatformTimeZone('UTC');
afterEach(() => cleanup());
