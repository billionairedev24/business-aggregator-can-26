import { useId, useRef, type ReactNode } from 'react';
import clsx from 'clsx';

export interface FileButtonProps {
  /** Button content, e.g. "Upload · PDF". */
  children: ReactNode;
  /** `accept` of the hidden file input ("application/pdf,image/png"). */
  accept?: string;
  onFile: (file: File) => void;
  disabled?: boolean;
  /** Shows a busy state while the upload runs. */
  pending?: boolean;
  variant?: 'secondary' | 'ghost' | 'primary';
  className?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean;
}

/** A button that opens the file picker (upload a document, a logo). Keyboard and screen-reader friendly. */
export function FileButton({ children, accept, onFile, disabled, pending, variant = 'secondary', className, ...aria }: FileButtonProps) {
  const input = useRef<HTMLInputElement>(null);
  const id = useId();
  return (
    <>
      <input
        ref={input}
        id={id}
        type="file"
        accept={accept}
        hidden
        tabIndex={-1}
        onChange={e => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ''; }}
      />
      <button
        type="button"
        className={clsx('btn', `btn-${variant}`, className)}
        disabled={disabled || pending}
        aria-busy={pending || undefined}
        aria-describedby={aria['aria-describedby']}
        aria-invalid={aria['aria-invalid'] || undefined}
        onClick={() => input.current?.click()}
      >{children}</button>
    </>
  );
}
