import * as a11yAddonAnnotations from '@storybook/addon-a11y/preview';
import { setProjectAnnotations } from '@storybook/react-vite';
import * as projectAnnotations from './preview';

// Apply the same decorators/parameters as Storybook itself (theme wrapper, tokens CSS, a11y test: 'error').
setProjectAnnotations([a11yAddonAnnotations, projectAnnotations]);
