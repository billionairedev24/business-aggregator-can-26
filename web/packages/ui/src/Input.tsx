import { forwardRef, useId, type InputHTMLAttributes } from 'react';
export interface InputProps extends InputHTMLAttributes<HTMLInputElement> { label: string; error?: string }
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input({ label, error, id, ...rest }, ref) {
  const auto = useId(); const fid = id ?? auto;
  return (
    <div className="field">
      <label htmlFor={fid}>{label}</label>
      <input ref={ref} id={fid} className="input" aria-invalid={!!error} aria-describedby={error ? fid + '-err' : undefined}
        style={error ? { borderColor: 'var(--color-danger-border)' } : undefined} {...rest} />
      {error && <div id={fid + '-err'} role="alert" style={{ color: 'var(--color-danger)', fontSize: 13, marginTop: 6 }}>{error}</div>}
    </div>
  );
});
