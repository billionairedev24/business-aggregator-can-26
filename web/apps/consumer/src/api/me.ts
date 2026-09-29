import { queryOptions } from '@tanstack/react-query';
export interface Me { name: string; email: string; initials: string; cartCount: number; activeOrders: number; defaultAddress?: { label: string } }
export const meQuery = queryOptions({ queryKey: ['me'], queryFn: async (): Promise<Me | null> => { const r = await fetch('/bff/me', { credentials: 'include' }); return r.status === 401 ? null : r.json(); } });
