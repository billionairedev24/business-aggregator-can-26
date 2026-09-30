import { createFileRoute } from '@tanstack/react-router';
import { pending } from '../features/shell/pending';

/** Home (S-46): search-first hero, Services / Shop / Food entry points. The header has no search here. */
export const Route = createFileRoute('/')({ ...pending('home') });
