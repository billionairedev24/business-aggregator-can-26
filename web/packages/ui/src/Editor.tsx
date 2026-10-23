import { useId, type ReactNode } from 'react';
import clsx from 'clsx';

/*
 * S-175: the parts of a back-office editor (a product, a service, a dish): titled sections, and the save bar that stays
 * at the bottom of the window while the form scrolls. Styles in styles/backoffice.css (semantic tokens only).
 */

export interface FormSectionProps {
  title: ReactNode;
  /** A line under the title: what the section is for. */
  description?: ReactNode;
  /** Beside the title (an "Add" button, a completion tag). */
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  id?: string;
}

/** One section of an editor: a white panel with its title (16 px bold), an optional line under it, then the fields. */
export function FormSection({ title, description, actions, children, className, id }: FormSectionProps) {
  const auto = useId();
  const head = id ?? `${auto}-h`;
  return (
    <section className={clsx('bo-fsection', className)} aria-labelledby={head}>
      <div className="bo-fsection-head">
        <div className="bo-fsection-text">
          <h2 className="bo-fsection-title" id={head}>{title}</h2>
          {description ? <p className="bo-fsection-desc">{description}</p> : null}
        </div>
        {actions ? <div className="bo-fsection-actions">{actions}</div> : null}
      </div>
      {children}
    </section>
  );
}

export interface SaveBarProps {
  /** The region's name for screen readers ("Save product"). */
  label: string;
  /** Where the record stands: a StatusTag ("Draft saved 9:00 a.m.", "In review"). */
  status?: ReactNode;
  /** A short line after the status ("Unsaved changes"; "Material changes go back to review"). */
  note?: ReactNode;
  /** The buttons: secondary first (Save draft), the primary last (Submit for vetting). */
  children: ReactNode;
  /** Default true: it stays at the bottom of the window. `false` draws it in place (the gallery). */
  sticky?: boolean;
  className?: string;
}

/**
 * The editor's save bar (sticky at the bottom of the window): the record's state on the left, the save buttons on the
 * right. It never covers the last field — the page leaves room for it.
 */
export function SaveBar({ label, status, note, children, sticky = true, className }: SaveBarProps) {
  return (
    <div className={clsx('bo-savebar', !sticky && 'bo-savebar-static', className)} role="region" aria-label={label}>
      <div className="bo-savebar-state">
        {status}
        {note ? <span className="bo-savebar-note">{note}</span> : null}
      </div>
      <div className="bo-savebar-actions">{children}</div>
    </div>
  );
}
