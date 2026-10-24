export type Pair = [fg: string, bg: string, min: number, where: string];
export interface Failure { fg: string; bg: string; min: number; where: string; ratio: number; a?: string; b?: string; reason?: string }
export declare const PAIRS: Pair[];
export declare function vet(colours: Record<string, string>, pairs?: Pair[]): Failure[];
export declare const FILL_ONLY: string[];
export declare const REJECTED: Pair[];
export declare function vetRoles(colours: Record<string, string>, pairs?: Pair[]): Failure[];
