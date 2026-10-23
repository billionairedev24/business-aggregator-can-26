import type { Meta, StoryObj } from '@storybook/react-vite';
import { I18nProvider } from './i18n';
import { Gallery, GALLERY_SECTIONS } from './gallery/Gallery';

/** The component gallery (S-168): every section, in English and in French. The gallery app shows the same page. */
const meta = { title: 'Gallery', parameters: { layout: 'fullscreen' } } satisfies Meta;
export default meta;
type S = StoryObj;

export const English: S = { render: () => <I18nProvider initial="en"><Gallery /></I18nProvider> };
export const French: S = { render: () => <I18nProvider initial="fr"><Gallery /></I18nProvider> };
export const Sections: S = {
  render: () => <I18nProvider initial="en">{GALLERY_SECTIONS.map(s => <Gallery key={s.id} section={s.id} />)}</I18nProvider>,
};
