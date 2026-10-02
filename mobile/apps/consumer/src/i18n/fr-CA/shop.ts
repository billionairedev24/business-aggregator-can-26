import type { shop as en } from '../en/shop';

export const shop: { [k in keyof typeof en]: string } = {};
