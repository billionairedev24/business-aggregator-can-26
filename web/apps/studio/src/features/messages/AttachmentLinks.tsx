import { FilePdf, Image } from '@phosphor-icons/react';
import { attachmentUrl, type Attachment } from './api';

/** Files inside a bubble: each opens in a new tab (served by the api with the business's access check). */
export function AttachmentLinks({ merchantId, files }: { merchantId: string; files: readonly Attachment[] }) {
  if (!files.length) return null;
  return (
    <ul className="nl-msg-files nl-msg-files-in">
      {files.map(f => (
        <li key={f.id} className="nl-msg-file">
          {f.contentType === 'application/pdf' ? <FilePdf size={16} aria-hidden /> : <Image size={16} aria-hidden />}
          <a href={attachmentUrl(merchantId, f.id)} target="_blank" rel="noreferrer" className="nl-msg-file-name">{f.fileName}</a>
        </li>
      ))}
    </ul>
  );
}
