import type { Meta, StoryObj } from '@storybook/react-vite';
import { I18nProvider } from './i18n';
import { Gallery, GALLERY_SECTIONS } from './gallery/Gallery';
import { ToastProvider } from './Feedback';

/** The component gallery (S-168): every section, in English and in French. The gallery app shows the same page. */
const meta = { title: 'Gallery', parameters: { layout: 'fullscreen' } } satisfies Meta;
export default meta;
type S = StoryObj;

export const English: S = { render: () => <I18nProvider initial="en"><Gallery /></I18nProvider> };
export const French: S = { render: () => <I18nProvider initial="fr"><Gallery /></I18nProvider> };
export const Sections: S = {
  // one page: the gallery's title (its banner) once, then every section on its own (S-180: the title repeated per section
  // gave the page a banner per section — axe landmark-unique)
  render: () => <I18nProvider initial="en"><ToastProvider>{GALLERY_SECTIONS.filter(s => !s.solo).map((s, i) => <Gallery key={s.id} section={s.id} head={i === 0} toasts={false} />)}</ToastProvider></I18nProvider>,
};
/** The back-office shell on its own (its banner and main would repeat inside the full gallery page). */
export const Shell: S = { render: () => <I18nProvider initial="en"><Gallery section="shell" /></I18nProvider> };
