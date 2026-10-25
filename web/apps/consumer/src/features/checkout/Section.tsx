import type { ReactNode, Ref } from 'react';

/**
 * A numbered section of a one-page checkout (E-15, design/revamp/a.html): the ink number box, the 18 px title, an
 * action at the right ("Change"). The shop checkout, the food checkout and the group order share it (checkout.css).
 */
export function Section({ n, id, title, action, headingRef, className, children }: {
  n: number; id: string; title: string; action?: ReactNode; headingRef?: Ref<HTMLHeadingElement>; className?: string; children: ReactNode;
}) {
  return (
    <section className={className ? `co-section ${className}` : 'co-section'} aria-labelledby={id}>
      <div className="co-section-head">
        <span className="co-num" aria-hidden>{n}</span>
        <h2 id={id} ref={headingRef} tabIndex={-1} className="co-section-title">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}
