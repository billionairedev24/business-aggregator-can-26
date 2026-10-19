export interface ResolvedTheme { color: Record<string, string>; ramp: Record<string, Record<string, string>>; [key: string]: unknown }
export declare function fontPackage(family: string): string;
export declare function semanticColours(palette: unknown, opts?: { chrome?: 'light' | 'dark' }): { colours: Record<string, string>; ramps: Record<string, Record<string, string>> };
export declare function resolveTheme(brand: unknown, palette: unknown, scale: unknown): ResolvedTheme;
export declare function markSvg(theme: unknown, size?: number): string;
