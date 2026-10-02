import type { services as en } from '../en/services';

export const services: { [k in keyof typeof en]: string } = {};
