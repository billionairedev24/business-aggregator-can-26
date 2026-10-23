import { forwardRef, useId, type InputHTMLAttributes } from 'react';
export interface InputProps extends InputHTMLAttributes<HTMLInputElement> { label: string; error?: string }
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input({ label, error, id, ...rest }, ref) {
  const auto = useId(); const fid = id ?? auto;
  return (
    <div className="field">
      <label htmlFor={fid}>{label}</label>
      {/* aria-invalid draws the danger edge (primitives.css), the message is the field's error line */}
      <input ref={ref} id={fid} className="input" aria-invalid={!!error} aria-describedby={error ? fid + '-err' : undefined} {...rest} />
      {error && <div id={fid + '-err'} role="alert" className="nl-error">{error}</div>}
    </div>
  );
});
