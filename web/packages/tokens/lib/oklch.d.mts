export declare function toOklch(hex: string): { l: number; c: number; h: number };
export declare function fromOklch(c: { l: number; c: number; h: number }): string;
export declare function mix(a: string, b: string, weightB: number): string;
export declare function contrast(a: string, b: string): number;
export declare function channels(hex: string): string;
export declare function readable(fg: string, bg: string, min: number, toward?: string): string;
