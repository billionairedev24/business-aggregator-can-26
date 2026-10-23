import clsx from 'clsx';
import { subjectFor, type Subject } from './subjects';

export interface PhotoWellProps {
  /** Whose icon to show: a category slug or id, a department slug, a cuisine code or a name (see `subjectFor`). */
  subject: string;
  /** Override the icon (the 404's compass). */
  draw?: Subject;
  className?: string;
}

/**
 * The designed fallback of a picture (S-181, locked direction A · Plainsight, design/revamp/a.html "Photo and
 * fallback"): the grey photo well with a dashed inner line and the category's icon. Shown for a listing without a
 * photo of its own and for the few slots the library deliberately has no photo for — never a drawn picture of the
 * item's name. Decorative: the text beside it names the thing.
 */
export function PhotoWell({ subject, draw, className }: PhotoWellProps) {
  const { main: Icon } = draw ?? subjectFor(subject);
  return (
    <span className={clsx('nl-well', className)} aria-hidden>
      <Icon className="nl-well-icon" weight="light" />
    </span>
  );
}
