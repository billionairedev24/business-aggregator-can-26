import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { configurePlatformTimeZone, I18nProvider } from '@northline/ui';
import { Gallery } from '@northline/ui/gallery';
import '@northline/tokens/tokens.css';
import '@northline/ui/styles.css';
import { galleryParams } from './params';

// The gallery's dates are fixed instants shown in UTC (screenshots must not move with the machine's zone).
configurePlatformTimeZone('UTC');
const { locale, section, open } = galleryParams(window.location.search);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nProvider initial={locale}>
      <Gallery section={section} open={open} photo="/sourdough.svg" />
    </I18nProvider>
  </StrictMode>,
);
