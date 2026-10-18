import { Logo } from '@northline/ui';

/** The brand's logo with the portal's name ("Console") on the dark header (redesign § 1.3, BO:30). */
export const Brand = ({ label }: { label: string }) => <Logo size={28} onDark product={label} />;
